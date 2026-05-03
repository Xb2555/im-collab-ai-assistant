package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.facade.HarnessFacade;
import com.lark.imcollab.common.facade.ImTaskCommandFacade;
import com.lark.imcollab.common.facade.TaskUserNotificationFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.service.TaskCancellationRegistry;
import com.lark.imcollab.planner.config.PlannerExecutionProperties;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.PlannerRetryService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DefaultImTaskCommandFacade implements ImTaskCommandFacade {

    private static final Logger log = LoggerFactory.getLogger(DefaultImTaskCommandFacade.class);

    private final PlannerSessionService sessionService;
    private final TaskBridgeService taskBridgeService;
    private final PlannerRetryService plannerRetryService;
    private final TaskRuntimeService taskRuntimeService;
    private final HarnessFacade harnessFacade;
    private final PlannerExecutionReviewService reviewService;
    private final List<TaskUserNotificationFacade> notificationFacades;
    private final AsyncTaskExecutor executionExecutor;
    private final ScheduledExecutorService executionTimeoutScheduler;
    private final PlannerExecutionProperties executionProperties;
    private final TaskCancellationRegistry cancellationRegistry;
    private final ConcurrentHashMap<String, ActiveExecution> activeExecutions = new ConcurrentHashMap<>();

    public DefaultImTaskCommandFacade(
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            PlannerRetryService plannerRetryService,
            TaskRuntimeService taskRuntimeService,
            HarnessFacade harnessFacade,
            PlannerExecutionReviewService reviewService,
            List<TaskUserNotificationFacade> notificationFacades,
            @Qualifier("executionTaskExecutor") AsyncTaskExecutor executionExecutor,
            ScheduledExecutorService executionTimeoutScheduler,
            PlannerExecutionProperties executionProperties,
            TaskCancellationRegistry cancellationRegistry
    ) {
        this.sessionService = sessionService;
        this.taskBridgeService = taskBridgeService;
        this.plannerRetryService = plannerRetryService;
        this.taskRuntimeService = taskRuntimeService;
        this.harnessFacade = harnessFacade;
        this.reviewService = reviewService;
        this.notificationFacades = notificationFacades == null ? List.of() : List.copyOf(notificationFacades);
        this.executionExecutor = executionExecutor;
        this.executionTimeoutScheduler = executionTimeoutScheduler;
        this.executionProperties = executionProperties;
        this.cancellationRegistry = cancellationRegistry;
    }

    @Override
    public PlanTaskSession confirmExecution(String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        if (session.getPlanningPhase() == PlanningPhaseEnum.EXECUTING) {
            return session;
        }
        cancellationRegistry.clear(taskId);
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
    public PlanTaskSession retryExecution(String taskId, String feedback) {
        PlanTaskSession session = sessionService.get(taskId);
        if (!plannerRetryService.isRetryable(taskId, session)) {
            return session;
        }
        cancellationRegistry.clear(taskId);
        PlanTaskSession retrying = plannerRetryService.prepareRetry(taskId, feedback);
        taskBridgeService.ensureTask(retrying);
        submitExecution(taskId);
        return retrying;
    }

    @Override
    public void cancelExecution(String taskId) {
        cancellationRegistry.markCancelled(taskId);
        ActiveExecution execution = activeExecutions.remove(taskId);
        if (execution != null) {
            execution.future.cancel(true);
            execution.timeoutFuture.cancel(false);
            log.info("Cancelled active execution: taskId={}", taskId);
        }
        try {
            harnessFacade.abortExecution(taskId);
        } catch (RuntimeException exception) {
            log.warn("Failed to abort harness execution: taskId={}, error={}", taskId, exception.getMessage());
        }
    }

    @Override
    public TaskRuntimeSnapshot getRuntimeSnapshot(String taskId) {
        return taskRuntimeService.getSnapshot(taskId);
    }

    private void submitExecution(String taskId) {
        if (activeExecutions.containsKey(taskId)) {
            log.info("Execution already in flight, skipping duplicate submit: taskId={}", taskId);
            return;
        }
        try {
            FutureTask<Void> future = new FutureTask<>(() -> {
                startHarness(taskId);
                return null;
            });
            ScheduledFuture<?> timeoutFuture = executionTimeoutScheduler.schedule(
                    () -> handleExecutionTimeout(taskId),
                    Math.max(1, executionProperties.getTimeoutSeconds()),
                    TimeUnit.SECONDS
            );
            ActiveExecution previous = activeExecutions.putIfAbsent(taskId, new ActiveExecution(future, timeoutFuture));
            if (previous != null) {
                timeoutFuture.cancel(false);
                future.cancel(true);
                log.info("Execution already tracked, cancelled duplicate future: taskId={}", taskId);
                return;
            }
            executionExecutor.execute(future);
        } catch (RuntimeException exception) {
            markExecutionFailed(taskId, "Failed to submit IM execution task", exception);
        }
    }

    private void startHarness(String taskId) {
        try {
            if (cancellationRegistry.isCancelled(taskId)) {
                log.info("Skip harness start because task is already cancelled: taskId={}", taskId);
                return;
            }
            harnessFacade.startExecution(taskId);
            if (!cancellationRegistry.isCancelled(taskId) && activeExecutions.containsKey(taskId)) {
                reviewService.reviewAndNotify(taskId);
            } else {
                log.warn("Skip execution review because task already timed out or finalized: taskId={}", taskId);
            }
        } catch (RuntimeException exception) {
            if (cancellationRegistry.isCancelled(taskId)) {
                log.info("Skip failure projection because task was cancelled: taskId={}", taskId);
                return;
            }
            markExecutionFailed(taskId, "Harness execution failed after IM confirmation", exception);
        } finally {
            clearActiveExecution(taskId);
        }
    }

    private void markExecutionFailed(String taskId, String message, RuntimeException exception) {
        clearActiveExecution(taskId);
        log.warn("{}: taskId={}, error={}", message, taskId, exception.getMessage(), exception);
        markFailedState(taskId, message + ": " + exception.getMessage());
    }

    private void handleExecutionTimeout(String taskId) {
        ActiveExecution execution = activeExecutions.remove(taskId);
        if (execution == null) {
            return;
        }
        execution.future.cancel(true);
        execution.timeoutFuture.cancel(false);
        String reason = "Execution timed out after " + Math.max(1, executionProperties.getTimeoutSeconds()) + " seconds";
        log.warn("{}", reason + ", taskId=" + taskId);
        markFailedState(taskId, reason);
    }

    private void markFailedState(String taskId, String reason) {
        PlanTaskSession failed = sessionService.get(taskId);
        failed.setPlanningPhase(PlanningPhaseEnum.FAILED);
        failed.setTransitionReason(reason);
        sessionService.save(failed);
        sessionService.publishEvent(taskId, "FAILED");
        TaskRuntimeSnapshot snapshot = loadRuntimeSnapshot(taskId);
        if (runtimeAlreadyFailed(snapshot)) {
            taskRuntimeService.syncTaskState(taskId, PlanningPhaseEnum.FAILED);
            snapshot = loadRuntimeSnapshot(taskId);
        } else {
            taskRuntimeService.projectPhaseTransition(taskId, PlanningPhaseEnum.FAILED, TaskEventTypeEnum.TASK_FAILED);
            snapshot = loadRuntimeSnapshot(taskId);
        }
        notifyExecutionFailed(failed, snapshot, taskId, reason);
    }

    private void clearActiveExecution(String taskId) {
        ActiveExecution execution = activeExecutions.remove(taskId);
        if (execution == null) {
            return;
        }
        execution.timeoutFuture.cancel(false);
    }

    private void notifyExecutionFailed(PlanTaskSession session, TaskRuntimeSnapshot snapshot, String taskId, String reason) {
        if (notificationFacades.isEmpty()) {
            return;
        }
        for (TaskUserNotificationFacade notificationFacade : notificationFacades) {
            try {
                notificationFacade.notifyExecutionFailed(session, snapshot, reason);
            } catch (RuntimeException notificationException) {
                log.warn("Failed to notify user about execution failure: taskId={}", taskId, notificationException);
            }
        }
    }

    private TaskRuntimeSnapshot loadRuntimeSnapshot(String taskId) {
        try {
            return taskRuntimeService.getSnapshot(taskId);
        } catch (RuntimeException snapshotException) {
            log.warn("Failed to load runtime snapshot for execution failure notification: taskId={}",
                    taskId, snapshotException);
            return null;
        }
    }

    private boolean runtimeAlreadyFailed(TaskRuntimeSnapshot snapshot) {
        return snapshot != null
                && snapshot.getTask() != null
                && snapshot.getTask().getStatus() == TaskStatusEnum.FAILED;
    }

    private record ActiveExecution(Future<?> future, ScheduledFuture<?> timeoutFuture) {
    }
}
