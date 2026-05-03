package com.lark.imcollab.planner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskStatus;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
}
