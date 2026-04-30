package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.facade.HarnessFacade;
import com.lark.imcollab.common.facade.TaskUserNotificationFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultImTaskCommandFacadeTest {

    private final PlannerSessionService sessionService = mock(PlannerSessionService.class);
    private final TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
    private final TaskRuntimeService taskRuntimeService = mock(TaskRuntimeService.class);
    private final HarnessFacade harnessFacade = mock(HarnessFacade.class);
    private final PlannerExecutionReviewService reviewService = mock(PlannerExecutionReviewService.class);
    private final TaskUserNotificationFacade notificationFacade = mock(TaskUserNotificationFacade.class);
    private final HoldingExecutor executor = new HoldingExecutor();
    private final DefaultImTaskCommandFacade facade = new DefaultImTaskCommandFacade(
            sessionService,
            taskBridgeService,
            taskRuntimeService,
            harnessFacade,
            reviewService,
            List.of(notificationFacade),
            executor
    );

    @Test
    void confirmExecutionReturnsBeforeHarnessStarts() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        when(sessionService.get("task-1")).thenReturn(session);

        PlanTaskSession returned = facade.confirmExecution("task-1");

        assertThat(returned.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.EXECUTING);
        verify(taskBridgeService).ensureTask(session);
        verify(sessionService).save(session);
        verify(sessionService).publishEvent("task-1", "EXECUTING");
        verify(taskRuntimeService).projectPhaseTransition(
                "task-1",
                PlanningPhaseEnum.EXECUTING,
                TaskEventTypeEnum.PLAN_APPROVED
        );
        verify(harnessFacade, never()).startExecution("task-1");

        executor.runAll();

        verify(harnessFacade).startExecution("task-1");
        verify(reviewService).reviewAndNotify("task-1");
    }

    @Test
    void harnessFailureMarksTaskFailedAndNotifiesUser() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        TaskRuntimeSnapshot failedSnapshot = TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder()
                        .taskId("task-1")
                        .status(TaskStatusEnum.FAILED)
                        .build())
                .build();
        when(sessionService.get("task-1")).thenReturn(session);
        when(taskRuntimeService.getSnapshot("task-1")).thenReturn(failedSnapshot);
        doThrow(new IllegalStateException("Error: server time out error"))
                .when(harnessFacade)
                .startExecution("task-1");

        facade.confirmExecution("task-1");
        executor.runAll();

        assertThat(session.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.FAILED);
        assertThat(session.getTransitionReason()).contains("server time out error");
        verify(taskRuntimeService).projectPhaseTransition(
                "task-1",
                PlanningPhaseEnum.FAILED,
                TaskEventTypeEnum.TASK_FAILED
        );
        verify(notificationFacade).notifyExecutionFailed(
                eq(session),
                eq(failedSnapshot),
                contains("server time out error")
        );
        verify(reviewService, never()).reviewAndNotify("task-1");
    }

    private static class HoldingExecutor implements Executor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            tasks.forEach(Runnable::run);
            tasks.clear();
        }
    }
}
