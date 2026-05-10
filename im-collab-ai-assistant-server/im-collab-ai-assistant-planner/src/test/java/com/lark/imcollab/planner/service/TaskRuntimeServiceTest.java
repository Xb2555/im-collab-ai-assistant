package com.lark.imcollab.planner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskStatus;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.planner.runtime.TaskRuntimeProjectionService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRuntimeServiceTest {

    @Test
    void syncTaskStateUpdatesPlannerAndDomainTaskStatus() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskRecord plannerTask = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.EXECUTING)
                .version(3)
                .build();
        Task domainTask = Task.builder()
                .taskId("task-1")
                .status(TaskStatus.EXECUTING)
                .build();
        when(stateStore.findSession("task-1")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-1")
                .version(4)
                .build()));
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(plannerTask));
        when(taskRepository.findById("task-1")).thenReturn(Optional.of(domainTask));
        TaskRuntimeService service = new TaskRuntimeService(
                stateStore,
                mock(PlanGraphBuilder.class),
                new ObjectMapper(),
                taskRepository,
                mock(ExecutionContractFactory.class),
                mock(TaskRuntimeProjectionService.class)
        );

        service.syncTaskState("task-1", PlanningPhaseEnum.COMPLETED);

        ArgumentCaptor<TaskRecord> plannerCaptor = ArgumentCaptor.forClass(TaskRecord.class);
        verify(stateStore).saveTask(plannerCaptor.capture());
        assertThat(plannerCaptor.getValue().getStatus()).isEqualTo(TaskStatusEnum.COMPLETED);
        assertThat(plannerCaptor.getValue().getVersion()).isEqualTo(4);

        ArgumentCaptor<Task> domainCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(domainCaptor.capture());
        assertThat(domainCaptor.getValue().getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(domainCaptor.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    void ensureRuntimeProjectionRecoversMissingTaskWithoutResettingExistingSteps() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        PlanGraphBuilder planGraphBuilder = mock(PlanGraphBuilder.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        TaskRuntimeSnapshot missingTaskSnapshot = TaskRuntimeSnapshot.builder()
                .steps(java.util.List.of(TaskStepRecord.builder()
                        .stepId("card-001")
                        .taskId("task-1")
                        .type(StepTypeEnum.DOC_CREATE)
                        .status(StepStatusEnum.COMPLETED)
                        .progress(100)
                        .build()))
                .build();
        when(projectionService.getSnapshot("task-1")).thenReturn(missingTaskSnapshot);
        when(stateStore.findSession("task-1")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .planBlueprint(PlanBlueprint.builder().taskBrief("写文档").build())
                .build()));
        TaskRuntimeService service = new TaskRuntimeService(
                stateStore,
                planGraphBuilder,
                new ObjectMapper(),
                taskRepository,
                mock(ExecutionContractFactory.class),
                projectionService
        );

        service.ensureRuntimeProjection("task-1");

        verify(projectionService).projectStage(
                org.mockito.ArgumentMatchers.any(PlanTaskSession.class),
                org.mockito.ArgumentMatchers.eq(TaskEventTypeEnum.TASK_COMPLETED),
                org.mockito.ArgumentMatchers.eq("Recovered missing runtime task")
        );
        verify(planGraphBuilder, never()).build(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(PlanBlueprint.class)
        );
    }

    @Test
    void appendUserInterventionWritesRuntimeEventWithFeedback() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        when(stateStore.findSession("task-1")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-1")
                .version(7)
                .build()));
        TaskRuntimeService service = new TaskRuntimeService(
                stateStore,
                mock(PlanGraphBuilder.class),
                new ObjectMapper(),
                mock(TaskRepository.class),
                mock(ExecutionContractFactory.class),
                mock(TaskRuntimeProjectionService.class)
        );

        service.appendUserIntervention("task-1", " 给一个大概的参考就好 ");

        ArgumentCaptor<com.lark.imcollab.common.model.entity.TaskEventRecord> eventCaptor =
                ArgumentCaptor.forClass(com.lark.imcollab.common.model.entity.TaskEventRecord.class);
        verify(stateStore).appendRuntimeEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getType()).isEqualTo(TaskEventTypeEnum.USER_INTERVENTION);
        assertThat(eventCaptor.getValue().getVersion()).isEqualTo(7);
        assertThat(eventCaptor.getValue().getPayloadJson()).contains("用户人工干预：给一个大概的参考就好");
    }

    @Test
    void projectPhaseTransitionMarksNonTerminalStepsSkippedWhenTaskAborted() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        TaskRecord plannerTask = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.EXECUTING)
                .version(2)
                .build();
        Task domainTask = Task.builder()
                .taskId("task-1")
                .status(TaskStatus.EXECUTING)
                .build();
        TaskStepRecord running = TaskStepRecord.builder()
                .taskId("task-1")
                .stepId("step-running")
                .status(StepStatusEnum.RUNNING)
                .build();
        TaskStepRecord ready = TaskStepRecord.builder()
                .taskId("task-1")
                .stepId("step-ready")
                .status(StepStatusEnum.READY)
                .build();
        TaskStepRecord completed = TaskStepRecord.builder()
                .taskId("task-1")
                .stepId("step-done")
                .status(StepStatusEnum.COMPLETED)
                .build();
        when(stateStore.findSession("task-1")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-1")
                .version(3)
                .build()));
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(plannerTask));
        when(taskRepository.findById("task-1")).thenReturn(Optional.of(domainTask));
        when(stateStore.findStepsByTaskId("task-1")).thenReturn(List.of(running, ready, completed));
        TaskRuntimeService service = new TaskRuntimeService(
                stateStore,
                mock(PlanGraphBuilder.class),
                new ObjectMapper(),
                taskRepository,
                mock(ExecutionContractFactory.class),
                mock(TaskRuntimeProjectionService.class)
        );

        service.projectPhaseTransition("task-1", PlanningPhaseEnum.ABORTED, TaskEventTypeEnum.TASK_CANCELLED);

        assertThat(running.getStatus()).isEqualTo(StepStatusEnum.SKIPPED);
        assertThat(ready.getStatus()).isEqualTo(StepStatusEnum.SKIPPED);
        assertThat(completed.getStatus()).isEqualTo(StepStatusEnum.COMPLETED);
        assertThat(running.getEndedAt()).isNotNull();
        assertThat(ready.getEndedAt()).isNotNull();
        verify(stateStore, times(2)).saveStep(org.mockito.ArgumentMatchers.any(TaskStepRecord.class));
    }

    @Test
    void reconcilePlanReadyProjectionReprojectsWhenRuntimeStillShowsOldExecutingPlan() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        PlanGraphBuilder planGraphBuilder = mock(PlanGraphBuilder.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        TaskRecord staleTask = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.EXECUTING)
                .currentStage(PlanningPhaseEnum.EXECUTING.name())
                .planVersion(1)
                .build();
        TaskStepRecord staleStep = TaskStepRecord.builder()
                .taskId("task-1")
                .stepId("card-001")
                .status(StepStatusEnum.RUNNING)
                .build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .planVersion(2)
                .planBlueprint(PlanBlueprint.builder()
                        .planCards(List.of(
                                UserPlanCard.builder().cardId("card-001").title("生成文档").type(PlanCardTypeEnum.DOC).status("COMPLETED").build(),
                                UserPlanCard.builder().cardId("card-002").title("生成PPT").type(PlanCardTypeEnum.PPT).status("PENDING").build()
                        ))
                        .build())
                .build();
        TaskPlanGraph graph = TaskPlanGraph.builder()
                .taskId("task-1")
                .goal("生成文档和PPT")
                .steps(List.of(
                        TaskStepRecord.builder().stepId("card-001").status(StepStatusEnum.COMPLETED).build(),
                        TaskStepRecord.builder().stepId("card-002").status(StepStatusEnum.READY).build()
                ))
                .build();
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(staleTask));
        when(stateStore.findStepsByTaskId("task-1")).thenReturn(List.of(staleStep));
        when(planGraphBuilder.build("task-1", session.getPlanBlueprint())).thenReturn(graph);
        ExecutionContractFactory executionContractFactory = mock(ExecutionContractFactory.class);
        when(executionContractFactory.build(session)).thenReturn(ExecutionContract.builder()
                .rawInstruction("生成文档和PPT")
                .clarifiedInstruction("生成文档和PPT")
                .taskBrief("生成文档和PPT")
                .allowedArtifacts(List.of("DOC", "PPT"))
                .build());

        TaskRuntimeService service = new TaskRuntimeService(
                stateStore,
                planGraphBuilder,
                new ObjectMapper(),
                taskRepository,
                executionContractFactory,
                projectionService
        );

        service.reconcilePlanReadyProjection(session, TaskEventTypeEnum.PLAN_ADJUSTED);

        verify(projectionService).projectPlanGraph(session, graph, TaskEventTypeEnum.PLAN_ADJUSTED);
    }
}
