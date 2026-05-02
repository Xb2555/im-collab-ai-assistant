package com.lark.imcollab.planner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlannerRetryServiceTest {

    private final PlannerStateStore stateStore = mock(PlannerStateStore.class);
    private final PlannerSessionService sessionService = mock(PlannerSessionService.class);
    private final PlannerRetryService retryService = new PlannerRetryService(
            stateStore,
            sessionService,
            new ObjectMapper()
    );

    @Test
    void prepareRetryResetsOnlyFailedAndRunningSteps() {
        PlanTaskSession failedSession = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.FAILED)
                .version(3)
                .build();
        TaskRecord failedTask = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.FAILED)
                .version(3)
                .artifactIds(List.of("artifact-1"))
                .build();
        TaskStepRecord completed = step("done", StepStatusEnum.COMPLETED, 0);
        TaskStepRecord failed = step("failed", StepStatusEnum.FAILED, 2);
        TaskStepRecord running = step("running", StepStatusEnum.RUNNING, 1);

        when(sessionService.get("task-1")).thenReturn(failedSession);
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(failedTask));
        when(stateStore.findStepsByTaskId("task-1")).thenReturn(List.of(completed, failed, running));
        when(sessionService.save(failedSession)).thenAnswer(invocation -> {
            failedSession.setVersion(failedSession.getVersion() + 1);
            return failedSession;
        });

        PlanTaskSession retrying = retryService.prepareRetry("task-1");

        assertThat(retrying.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.EXECUTING);
        assertThat(failed.getStatus()).isEqualTo(StepStatusEnum.READY);
        assertThat(failed.getRetryCount()).isEqualTo(3);
        assertThat(failed.getStartedAt()).isNull();
        assertThat(failed.getEndedAt()).isNull();
        assertThat(running.getStatus()).isEqualTo(StepStatusEnum.READY);
        assertThat(running.getRetryCount()).isEqualTo(2);
        assertThat(completed.getStatus()).isEqualTo(StepStatusEnum.COMPLETED);
        verify(stateStore, times(2)).saveStep(any(TaskStepRecord.class));
        verify(stateStore).saveTask(argThat(task ->
                task.getStatus() == TaskStatusEnum.EXECUTING
                        && task.getVersion() == 4
                        && task.getArtifactIds().contains("artifact-1")
        ));

        ArgumentCaptor<TaskEventRecord> eventCaptor = ArgumentCaptor.forClass(TaskEventRecord.class);
        verify(stateStore, times(2)).appendRuntimeEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(TaskEventRecord::getType)
                .containsExactly(TaskEventTypeEnum.STEP_RETRY_SCHEDULED, TaskEventTypeEnum.PLAN_APPROVED);
        verify(sessionService).publishEvent("task-1", "EXECUTING");
    }

    @Test
    void taskRecordFailedStatusIsRetryableEvenIfSessionIsStale() {
        PlanTaskSession executingSession = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.FAILED)
                .build()));

        assertThat(retryService.isRetryable("task-1", executingSession)).isTrue();
    }

    private static TaskStepRecord step(String id, StepStatusEnum status, int retryCount) {
        return TaskStepRecord.builder()
                .stepId(id)
                .taskId("task-1")
                .name(id)
                .status(status)
                .retryCount(retryCount)
                .progress(80)
                .version(1)
                .build();
    }
}
