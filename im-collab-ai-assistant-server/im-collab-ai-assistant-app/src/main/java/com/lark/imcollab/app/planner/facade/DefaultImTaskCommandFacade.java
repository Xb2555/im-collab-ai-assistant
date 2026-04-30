package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.facade.HarnessFacade;
import com.lark.imcollab.common.facade.ImTaskCommandFacade;
import com.lark.imcollab.common.facade.TaskUserNotificationFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;

@Service
public class DefaultImTaskCommandFacade implements ImTaskCommandFacade {

    private static final Logger log = LoggerFactory.getLogger(DefaultImTaskCommandFacade.class);

    private final PlannerSessionService sessionService;
    private final TaskBridgeService taskBridgeService;
    private final TaskRuntimeService taskRuntimeService;
    private final HarnessFacade harnessFacade;
    private final PlannerExecutionReviewService reviewService;
    private final List<TaskUserNotificationFacade> notificationFacades;
    private final Executor executionExecutor;

    public DefaultImTaskCommandFacade(
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            TaskRuntimeService taskRuntimeService,
            HarnessFacade harnessFacade,
            PlannerExecutionReviewService reviewService,
            List<TaskUserNotificationFacade> notificationFacades,
            @Qualifier("plannerTaskExecutor") Executor executionExecutor
    ) {
        this.sessionService = sessionService;
        this.taskBridgeService = taskBridgeService;
        this.taskRuntimeService = taskRuntimeService;
        this.harnessFacade = harnessFacade;
        this.reviewService = reviewService;
        this.notificationFacades = notificationFacades == null ? List.of() : List.copyOf(notificationFacades);
        this.executionExecutor = executionExecutor;
    }

    @Override
    public PlanTaskSession confirmExecution(String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        if (session.getPlanningPhase() == PlanningPhaseEnum.EXECUTING) {
            return session;
        }
        taskBridgeService.ensureTask(session);
        session.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
        session.setTransitionReason("User confirmed execution from IM");
        sessionService.save(session);
        sessionService.publishEvent(taskId, "EXECUTING");
        taskRuntimeService.projectPhaseTransition(taskId, PlanningPhaseEnum.EXECUTING, TaskEventTypeEnum.PLAN_APPROVED);
        submitExecution(taskId);
        return session;
    }

    @Override
    public TaskRuntimeSnapshot getRuntimeSnapshot(String taskId) {
        return taskRuntimeService.getSnapshot(taskId);
    }

    private void submitExecution(String taskId) {
        try {
            executionExecutor.execute(() -> startHarness(taskId));
        } catch (RuntimeException exception) {
            markExecutionFailed(taskId, "Failed to submit IM execution task", exception);
        }
    }

    private void startHarness(String taskId) {
        try {
            harnessFacade.startExecution(taskId);
            reviewService.reviewAndNotify(taskId);
        } catch (RuntimeException exception) {
            markExecutionFailed(taskId, "Harness execution failed after IM confirmation", exception);
        }
    }

    private void markExecutionFailed(String taskId, String message, RuntimeException exception) {
        log.warn("{}: taskId={}, error={}", message, taskId, exception.getMessage(), exception);
        PlanTaskSession failed = sessionService.get(taskId);
        failed.setPlanningPhase(PlanningPhaseEnum.FAILED);
        failed.setTransitionReason(message + ": " + exception.getMessage());
        sessionService.save(failed);
        sessionService.publishEvent(taskId, "FAILED");
        taskRuntimeService.projectPhaseTransition(taskId, PlanningPhaseEnum.FAILED, TaskEventTypeEnum.TASK_FAILED);
        notifyExecutionFailed(failed, taskId, failed.getTransitionReason());
    }

    private void notifyExecutionFailed(PlanTaskSession session, String taskId, String reason) {
        if (notificationFacades.isEmpty()) {
            return;
        }
        TaskRuntimeSnapshot snapshot = null;
        try {
            snapshot = taskRuntimeService.getSnapshot(taskId);
        } catch (RuntimeException snapshotException) {
            log.warn("Failed to load runtime snapshot for execution failure notification: taskId={}",
                    taskId, snapshotException);
        }
        for (TaskUserNotificationFacade notificationFacade : notificationFacades) {
            try {
                notificationFacade.notifyExecutionFailed(session, snapshot, reason);
            } catch (RuntimeException notificationException) {
                log.warn("Failed to notify user about execution failure: taskId={}", taskId, notificationException);
            }
        }
    }
}
