/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.engine.test.api.runtime.migration;

import static org.camunda.bpm.engine.test.api.runtime.migration.ModifiableBpmnModelInstance.modify;
import static org.camunda.bpm.engine.test.util.ActivityInstanceAssert.assertThat;
import static org.camunda.bpm.engine.test.util.ActivityInstanceAssert.describeActivityInstanceTree;
import static org.camunda.bpm.engine.test.util.ExecutionAssert.assertThat;
import static org.camunda.bpm.engine.test.util.ExecutionAssert.describeExecutionTree;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Collections;
import java.util.List;

import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.migration.MigrationPlan;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.test.ProcessEngineRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

public class MigrationRemoveBoundaryEventsTest {

  public static final String AFTER_BOUNDARY_TASK = "afterBoundary";
  public static final String MESSAGE_NAME = "Message";
  public static final String SIGNAL_NAME = "Signal";
  public static final String TIMER_DATE = "2016-02-11T12:13:14Z";
  public static final String ERROR_CODE = "Error";
  public static final String ESCALATION_CODE = "Escalation";

  protected ProcessEngineRule rule = new ProcessEngineRule();
  protected MigrationTestRule testHelper = new MigrationTestRule(rule);

  @Rule
  public RuleChain ruleChain = RuleChain.outerRule(rule).around(testHelper);

  protected ProcessEngine processEngine;
  protected RuntimeService runtimeService;
  protected TaskService taskService;
  protected ManagementService managementService;

  protected ProcessInstanceSnapshot snapshotBeforeMigration;
  protected ProcessInstanceSnapshot snapshotAfterMigration;

  @Before
  public void initServices() {
    processEngine = rule.getProcessEngine();
    runtimeService = rule.getRuntimeService();
    taskService = rule.getTaskService();
    managementService = rule.getManagementService();
  }

  @Test
  public void testRemoveMessageBoundaryEventFromUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.ONE_TASK_PROCESS)
      .addMessageBoundaryEventWithUserTask("userTask", MESSAGE_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.ONE_TASK_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree("userTask").scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveMessageBoundaryEventFromScopeUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SCOPE_TASK_PROCESS)
      .addMessageBoundaryEventWithUserTask("userTask", MESSAGE_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SCOPE_TASK_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "userTask"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveMessageBoundaryEventFromConcurrentUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.PARALLEL_GATEWAY_PROCESS)
      .addMessageBoundaryEventWithUserTask("userTask1", MESSAGE_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.PARALLEL_GATEWAY_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("userTask1", "userTask1")
      .mapActivities("userTask2", "userTask2")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask1").concurrent().noScope()
          .up()
          .child("userTask2").concurrent().noScope()
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .activity("userTask1", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask1").getId())
        .activity("userTask2", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask2").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask1", "userTask2");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveMessageBoundaryEventFromConcurrentScopeUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.PARALLEL_SCOPE_TASKS)
      .addMessageBoundaryEventWithUserTask("userTask1", MESSAGE_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.PARALLEL_SCOPE_TASKS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("userTask1", "userTask1")
      .mapActivities("userTask2", "userTask2")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child(null).concurrent().noScope()
          .child("userTask1").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "userTask1"))
          .up().up()
          .child(null).concurrent().noScope()
          .child("userTask2").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "userTask2"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .activity("userTask1", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask1").getId())
        .activity("userTask2", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask2").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask1", "userTask2");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveMessageBoundaryEventFromSubProcess() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SUBPROCESS_PROCESS)
      .addMessageBoundaryEventWithUserTask("subProcess", MESSAGE_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess", "subProcess")
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess").getId())
          .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveMessageBoundaryEventFromSubProcessWithScopeUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SCOPE_TASK_SUBPROCESS_PROCESS)
      .addMessageBoundaryEventWithUserTask("subProcess", MESSAGE_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SCOPE_TASK_SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess", "subProcess")
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child(null).scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess"))
          .child("userTask").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "userTask"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess").getId())
          .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveMessageBoundaryEventFromParallelSubProcess() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.PARALLEL_SUBPROCESS_PROCESS)
      .addMessageBoundaryEventWithUserTask("subProcess1", MESSAGE_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.PARALLEL_SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess1", "subProcess1")
      .mapActivities("subProcess2", "subProcess2")
      .mapActivities("userTask1", "userTask1")
      .mapActivities("userTask2", "userTask2")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child(null).concurrent().noScope()
          .child("userTask1").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess1"))
          .up().up()
          .child(null).concurrent().noScope()
          .child("userTask2").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess2"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess1", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess1").getId())
          .activity("userTask1", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask1").getId())
        .endScope()
        .beginScope("subProcess2", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess2").getId())
          .activity("userTask2", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask2").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask1", "userTask2");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveMessageBoundaryEventFromUserTaskInSubProcess() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SUBPROCESS_PROCESS)
      .addMessageBoundaryEventWithUserTask("userTask", MESSAGE_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess", "subProcess")
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess").getId())
          .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveSignalBoundaryEventFromUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.ONE_TASK_PROCESS)
      .addSignalBoundaryEventWithUserTask("userTask", SIGNAL_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.ONE_TASK_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree("userTask").scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveSignalBoundaryEventFromScopeUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SCOPE_TASK_PROCESS)
      .addSignalBoundaryEventWithUserTask("userTask", SIGNAL_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SCOPE_TASK_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope()
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveSignalBoundaryEventFromConcurrentUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.PARALLEL_GATEWAY_PROCESS)
      .addSignalBoundaryEventWithUserTask("userTask1", SIGNAL_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.PARALLEL_GATEWAY_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("userTask1", "userTask1")
      .mapActivities("userTask2", "userTask2")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask1").concurrent().noScope()
          .up()
          .child("userTask2").concurrent().noScope()
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .activity("userTask1", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask1").getId())
        .activity("userTask2", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask2").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask1", "userTask2");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveSignalBoundaryEventFromConcurrentScopeUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.PARALLEL_SCOPE_TASKS)
      .addSignalBoundaryEventWithUserTask("userTask1", SIGNAL_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.PARALLEL_SCOPE_TASKS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("userTask1", "userTask1")
      .mapActivities("userTask2", "userTask2")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child(null).concurrent().noScope()
          .child("userTask1").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "userTask1"))
          .up().up()
          .child(null).concurrent().noScope()
          .child("userTask2").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "userTask2"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .activity("userTask1", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask1").getId())
        .activity("userTask2", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask2").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask1", "userTask2");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveSignalBoundaryEventFromSubProcess() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SUBPROCESS_PROCESS)
      .addSignalBoundaryEventWithUserTask("subProcess", SIGNAL_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess", "subProcess")
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess").getId())
          .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveSignalBoundaryEventFromSubProcessWithScopeUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SCOPE_TASK_SUBPROCESS_PROCESS)
      .addSignalBoundaryEventWithUserTask("subProcess", SIGNAL_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SCOPE_TASK_SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess", "subProcess")
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child(null).scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess"))
          .child("userTask").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "userTask"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess").getId())
          .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveSignalBoundaryEventFromParallelSubProcess() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.PARALLEL_SUBPROCESS_PROCESS)
      .addSignalBoundaryEventWithUserTask("subProcess1", SIGNAL_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.PARALLEL_SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess1", "subProcess1")
      .mapActivities("subProcess2", "subProcess2")
      .mapActivities("userTask1", "userTask1")
      .mapActivities("userTask2", "userTask2")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child(null).concurrent().noScope()
          .child("userTask1").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess1"))
          .up().up()
          .child(null).concurrent().noScope()
          .child("userTask2").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess2"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess1", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess1").getId())
          .activity("userTask1", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask1").getId())
        .endScope()
        .beginScope("subProcess2", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess2").getId())
          .activity("userTask2", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask2").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask1", "userTask2");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveSignalBoundaryEventFromUserTaskInSubProcess() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SUBPROCESS_PROCESS)
      .addSignalBoundaryEventWithUserTask("userTask", SIGNAL_NAME, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess", "subProcess")
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess")
          .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoEventSubscriptionExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveTimerBoundaryEventFromUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.ONE_TASK_PROCESS)
      .addTimerDateBoundaryEventWithUserTask("userTask", TIMER_DATE, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.ONE_TASK_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree("userTask").scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoJobExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveTimerBoundaryEventFromScopeUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SCOPE_TASK_PROCESS)
      .addTimerDateBoundaryEventWithUserTask("userTask", TIMER_DATE, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SCOPE_TASK_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope()
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoJobExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveTimerBoundaryEventFromConcurrentUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.PARALLEL_GATEWAY_PROCESS)
      .addTimerDateBoundaryEventWithUserTask("userTask1", TIMER_DATE, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.PARALLEL_GATEWAY_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("userTask1", "userTask1")
      .mapActivities("userTask2", "userTask2")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask1").concurrent().noScope()
          .up()
          .child("userTask2").concurrent().noScope()
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .activity("userTask1", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask1").getId())
        .activity("userTask2", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask2").getId())
        .done());

    assertNoJobExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask1", "userTask2");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveTimerBoundaryEventFromConcurrentScopeUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.PARALLEL_SCOPE_TASKS)
      .addTimerDateBoundaryEventWithUserTask("userTask1", TIMER_DATE, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.PARALLEL_SCOPE_TASKS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("userTask1", "userTask1")
      .mapActivities("userTask2", "userTask2")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child(null).concurrent().noScope()
          .child("userTask1").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "userTask1"))
          .up().up()
          .child(null).concurrent().noScope()
          .child("userTask2").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "userTask2"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .activity("userTask1", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask1").getId())
        .activity("userTask2", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask2").getId())
        .done());

    assertNoJobExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask1", "userTask2");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveTimerBoundaryEventFromSubProcess() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SUBPROCESS_PROCESS)
      .addTimerDateBoundaryEventWithUserTask("subProcess", TIMER_DATE, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess", "subProcess")
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess").getId())
          .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoJobExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveTimerBoundaryEventFromSubProcessWithScopeUserTask() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SCOPE_TASK_SUBPROCESS_PROCESS)
      .addTimerDateBoundaryEventWithUserTask("subProcess", TIMER_DATE, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SCOPE_TASK_SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess", "subProcess")
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child(null).scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess"))
          .child("userTask").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "userTask"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess").getId())
          .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoJobExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveTimerBoundaryEventFromParallelSubProcess() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.PARALLEL_SUBPROCESS_PROCESS)
      .addTimerDateBoundaryEventWithUserTask("subProcess1", TIMER_DATE, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.PARALLEL_SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess1", "subProcess1")
      .mapActivities("subProcess2", "subProcess2")
      .mapActivities("userTask1", "userTask1")
      .mapActivities("userTask2", "userTask2")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child(null).concurrent().noScope()
          .child("userTask1").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess1"))
          .up().up()
          .child(null).concurrent().noScope()
          .child("userTask2").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess2"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess1", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess1").getId())
          .activity("userTask1", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask1").getId())
        .endScope()
        .beginScope("subProcess2", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess2").getId())
          .activity("userTask2", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask2").getId())
        .done());

    assertNoJobExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask1", "userTask2");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveTimerBoundaryEventFromUserTaskInSubProcess() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SUBPROCESS_PROCESS)
      .addTimerDateBoundaryEventWithUserTask("userTask", TIMER_DATE, AFTER_BOUNDARY_TASK)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess", "subProcess")
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess").getId())
          .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoJobExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveMultipleBoundaryEvents() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SUBPROCESS_PROCESS)
      .addTimerDateBoundaryEvent("subProcess", TIMER_DATE)
      .addMessageBoundaryEvent("userTask", MESSAGE_NAME)
      .addSignalBoundaryEvent("userTask", SIGNAL_NAME)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess", "subProcess")
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess").getId())
          .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    assertNoEventSubscriptionExists();
    assertNoJobExists();

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveErrorBoundaryEventFromSubProcess() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SUBPROCESS_PROCESS)
      .addErrorBoundaryEvent("subProcess", ERROR_CODE)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess", "subProcess")
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess").getId())
        .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }

  @Test
  public void testRemoveEscalationBoundaryEventFromSubProcess() {
    // given
    ProcessDefinition sourceProcessDefinition = testHelper.deploy(modify(ProcessModels.SUBPROCESS_PROCESS)
      .addEscalationBoundaryEvent("subProcess", ESCALATION_CODE)
    );
    ProcessDefinition targetProcessDefinition = testHelper.deploy(ProcessModels.SUBPROCESS_PROCESS);

    MigrationPlan migrationPlan = runtimeService
      .createMigrationPlan(sourceProcessDefinition.getId(), targetProcessDefinition.getId())
      .mapActivities("subProcess", "subProcess")
      .mapActivities("userTask", "userTask")
      .build();

    // when
    createProcessInstanceAndMigrate(migrationPlan);

    // then
    assertThat(snapshotAfterMigration.getExecutionTree())
      .hasProcessDefinitionId(targetProcessDefinition.getId())
      .matches(
        describeExecutionTree(null).scope().id(snapshotBeforeMigration.getProcessInstanceId())
          .child("userTask").scope().id(testHelper.getSingleExecutionIdForActivity(snapshotBeforeMigration.getActivityTree(), "subProcess"))
          .done());

    assertThat(snapshotAfterMigration.getActivityTree()).hasStructure(
      describeActivityInstanceTree(targetProcessDefinition.getId())
        .beginScope("subProcess", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "subProcess").getId())
        .activity("userTask", testHelper.getSingleActivityInstance(snapshotBeforeMigration.getActivityTree(), "userTask").getId())
        .done());

    // and it is possible to successfully complete the migrated instance
    completeTasks("userTask");
    testHelper.assertProcessEnded(snapshotBeforeMigration.getProcessInstanceId());
  }


  // helper

  protected void createProcessInstanceAndMigrate(MigrationPlan migrationPlan) {
    ProcessInstance processInstance = runtimeService.startProcessInstanceById(migrationPlan.getSourceProcessDefinitionId());
    snapshotBeforeMigration = testHelper.takeFullProcessInstanceSnapshot(processInstance);
    runtimeService.executeMigrationPlan(migrationPlan, Collections.singletonList(snapshotBeforeMigration.getProcessInstanceId()));
    snapshotAfterMigration = testHelper.takeFullProcessInstanceSnapshot(processInstance);
  }

  protected void completeTasks(String... taskKeys) {
    List<Task> tasks = taskService.createTaskQuery().taskDefinitionKeyIn(taskKeys).list();
    assertEquals(taskKeys.length, tasks.size());
    for (Task task : tasks) {
      assertNotNull(task);
      taskService.complete(task.getId());
    }
  }

  protected void assertNoEventSubscriptionExists() {
    assertEquals(0, snapshotAfterMigration.getEventSubscriptions().size());
  }

  protected void assertNoJobExists() {
    assertEquals(0, snapshotAfterMigration.getJobs().size());
  }

}