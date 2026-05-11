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
import com.lark.imcollab.common.service.ExecutionAttemptContext;
import com.lark.imcollab.harness.support.ExecutionBusyException;
import com.lark.imcollab.harness.support.HarnessExecutionLockRecoveryService;
import com.lark.imcollab.planner.config.PlannerExecutionProperties;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.PlannerRetryService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.UUID;

public class DefaultImTaskCommandFacade implements ImTaskCommandFacade {

    private static final Logger log = LoggerFactory.getLogger(DefaultImTaskCommandFacade.class);
    private static final long EXECUTION_DRAIN_TIMEOUT_MILLIS = 240_000L;
    private static final long EXECUTION_DRAIN_POLL_MILLIS = 25L;
    private static final long BUSY_RETRY_DELAY_SECONDS = 5L;

    private final PlannerSessionService sessionService;
    private final TaskBridgeService taskBridgeService;
    private final PlannerRetryService plannerRetryService;
    private final TaskRuntimeService taskRuntimeService;
    private final HarnessFacade harnessFacade;
    private final PlannerExecutionReviewService reviewService;
    private final TaskArtifactResetService taskArtifactResetService;
    private final List<TaskUserNotificationFacade> notificationFacades;
    private final AsyncTaskExecutor executionExecutor;
    private final ScheduledExecutorService executionTimeoutScheduler;
    private final PlannerExecutionProperties executionProperties;
    private final TaskCancellationRegistry cancellationRegistry;
    private final HarnessExecutionLockRecoveryService lockRecoveryService;
    private final ConcurrentHashMap<String, ActiveExecution> activeExecutions = new ConcurrentHashMap<>();

    public DefaultImTaskCommandFacade(
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            PlannerRetryService plannerRetryService,
            TaskRuntimeService taskRuntimeService,
            HarnessFacade harnessFacade,
            PlannerExecutionReviewService reviewService,
            TaskArtifactResetService taskArtifactResetService,
            List<TaskUserNotificationFacade> notificationFacades,
            @Qualifier("executionTaskExecutor") AsyncTaskExecutor executionExecutor,
            ScheduledExecutorService executionTimeoutScheduler,
            PlannerExecutionProperties executionProperties,
            TaskCancellationRegistry cancellationRegistry,
            HarnessExecutionLockRecoveryService lockRecoveryService
    ) {
        this.sessionService = sessionService;
        this.taskBridgeService = taskBridgeService;
        this.plannerRetryService = plannerRetryService;
        this.taskRuntimeService = taskRuntimeService;
        this.harnessFacade = harnessFacade;
        this.reviewService = reviewService;
        this.taskArtifactResetService = taskArtifactResetService;
        this.notificationFacades = notificationFacades == null ? List.of() : List.copyOf(notificationFacades);
        this.executionExecutor = executionExecutor;
        this.executionTimeoutScheduler = executionTimeoutScheduler;
        this.executionProperties = executionProperties;
        this.cancellationRegistry = cancellationRegistry;
        this.lockRecoveryService = lockRecoveryService;
    }

    @Override
    public PlanTaskSession confirmExecution(String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        if (session.getPlanningPhase() == PlanningPhaseEnum.EXECUTING) {
            return session;
        }
        if (!awaitPreviousExecutionDrain(taskId)) {
            log.warn("Skip execution restart because previous run is still draining: taskId={}", taskId);
            return session;
        }
        clearStaleExecutionLocks(taskId);
        cancellationRegistry.clear(taskId);
        if (!shouldPreserveExistingArtifacts(session)) {
            taskArtifactResetService.clearGeneratedArtifactsBeforeExecution(taskId);
        }
        taskBridgeService.ensureTask(session);
        String executionAttemptId = UUID.randomUUID().toString();
        session.setActiveExecutionAttemptId(executionAttemptId);
        session.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
        session.setTransitionReason("User confirmed execution from IM");
        clearPreserveExistingArtifactsFlag(session);
        sessionService.save(session);
        sessionService.publishEvent(taskId, "EXECUTING");
        taskRuntimeService.projectPhaseTransition(taskId, PlanningPhaseEnum.EXECUTING, TaskEventTypeEnum.PLAN_APPROVED);
        submitExecution(taskId, executionAttemptId);
        return session;
    }

    @Override
    public PlanTaskSession retryExecution(String taskId, String feedback) {
        PlanTaskSession session = sessionService.get(taskId);
        if (!plannerRetryService.isRetryable(taskId, session)) {
            return session;
        }
        if (!awaitPreviousExecutionDrain(taskId)) {
            log.warn("Skip retry because previous run is still draining: taskId={}", taskId);
            return session;
        }
        clearStaleExecutionLocks(taskId);
        cancellationRegistry.clear(taskId);
        PlanTaskSession retrying = plannerRetryService.prepareRetry(taskId, feedback);
        if (!shouldPreserveExistingArtifacts(retrying)) {
            taskArtifactResetService.clearGeneratedArtifactsBeforeExecution(taskId);
        }
        String executionAttemptId = UUID.randomUUID().toString();
        retrying.setActiveExecutionAttemptId(executionAttemptId);
        clearPreserveExistingArtifactsFlag(retrying);
        sessionService.save(retrying);
        taskBridgeService.ensureTask(retrying);
        submitExecution(taskId, executionAttemptId);
        return retrying;
    }

    @Override
    public void interruptExecution(String taskId) {
        cancellationRegistry.markCancelled(taskId);
        ActiveExecution execution = activeExecutions.get(taskId);
        if (execution != null) {
            execution.future.cancel(true);
            execution.timeoutFuture.cancel(false);
            if (!execution.hasStarted()) {
                activeExecutions.remove(taskId, execution);
            }
            log.info("Interrupted active execution for replan: taskId={}, attempt={}",
                    taskId, execution.executionAttemptId());
        }
    }

    @Override
    public void cancelExecution(String taskId) {
        interruptExecution(taskId);
        submitAbort(taskId);
    }

    @Override
    public TaskRuntimeSnapshot getRuntimeSnapshot(String taskId) {
        return taskRuntimeService.getSnapshot(taskId);
    }

    private void submitExecution(String taskId, String executionAttemptId) {
        if (activeExecutions.containsKey(taskId)) {
            log.info("Execution already in flight, skipping duplicate submit: taskId={}", taskId);
            return;
        }
        try {
            AtomicBoolean started = new AtomicBoolean(false);
            FutureTask<Void> future = new FutureTask<>(() -> {
                started.set(true);
                startHarness(taskId, executionAttemptId);
                return null;
            });
            ScheduledFuture<?> timeoutFuture = executionTimeoutScheduler.schedule(
                    () -> handleExecutionTimeout(taskId),
                    Math.max(1, executionProperties.getTimeoutSeconds()),
                    TimeUnit.SECONDS
            );
            ActiveExecution execution = new ActiveExecution(future, timeoutFuture, started, executionAttemptId);
            ActiveExecution previous = activeExecutions.putIfAbsent(taskId, execution);
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

    private void submitAbort(String taskId) {
        try {
            executionExecutor.execute(() -> {
                try {
                    harnessFacade.abortExecution(taskId);
                } catch (RuntimeException exception) {
                    log.warn("Failed to abort harness execution: taskId={}, error={}", taskId, exception.getMessage());
                }
            });
        } catch (RuntimeException exception) {
            log.warn("Failed to submit harness abort task: taskId={}, error={}", taskId, exception.getMessage());
        }
    }

    private void startHarness(String taskId, String executionAttemptId) {
        try {
            if (cancellationRegistry.isCancelled(taskId)) {
                log.info("Skip harness start because task is already cancelled: taskId={}", taskId);
                return;
            }
            try (ExecutionAttemptContext.Scope ignored = ExecutionAttemptContext.open(taskId, executionAttemptId)) {
                harnessFacade.startExecution(taskId);
            }
            if (!cancellationRegistry.isCancelled(taskId) && activeExecutions.containsKey(taskId)) {
                reviewService.reviewAndNotify(taskId);
            } else {
                log.warn("Skip execution review because task already timed out or finalized: taskId={}", taskId);
            }
        } catch (RuntimeException exception) {
            if (isExecutionBusy(exception) && shouldRetryBusyExecution(taskId, executionAttemptId)) {
                log.info("Harness execution is still busy, will retry same attempt: taskId={}, attempt={}",
                        taskId, executionAttemptId);
                scheduleBusyRetry(taskId, executionAttemptId);
                return;
            }
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

    private boolean shouldRetryBusyExecution(String taskId, String executionAttemptId) {
        if (cancellationRegistry.isCancelled(taskId)) {
            return false;
        }
        try {
            PlanTaskSession session = sessionService.get(taskId);
            return session != null
                    && session.getPlanningPhase() == PlanningPhaseEnum.EXECUTING
                    && executionAttemptId != null
                    && executionAttemptId.equals(session.getActiveExecutionAttemptId());
        } catch (RuntimeException exception) {
            log.warn("Failed to verify busy execution retry state: taskId={}, attempt={}",
                    taskId, executionAttemptId, exception);
            return false;
        }
    }

    private void scheduleBusyRetry(String taskId, String executionAttemptId) {
        clearActiveExecution(taskId);
        try {
            executionTimeoutScheduler.schedule(
                    () -> submitExecution(taskId, executionAttemptId),
                    BUSY_RETRY_DELAY_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (RuntimeException scheduleException) {
            markExecutionFailed(taskId, "Failed to schedule busy execution retry", scheduleException);
        }
    }

    private boolean isExecutionBusy(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ExecutionBusyException) {
                return true;
            }
            if (current.getMessage() != null && current.getMessage().contains("Task is already executing")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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

    private boolean shouldPreserveExistingArtifacts(PlanTaskSession session) {
        return session != null
                && session.getIntakeState() != null
                && session.getIntakeState().isPreserveExistingArtifactsOnExecution();
    }

    private void clearStaleExecutionLocks(String taskId) {
        if (lockRecoveryService == null) {
            return;
        }
        lockRecoveryService.clearStaleTaskLocks(taskId);
    }

    private void clearPreserveExistingArtifactsFlag(PlanTaskSession session) {
        if (session == null || session.getIntakeState() == null) {
            return;
        }
        session.getIntakeState().setPreserveExistingArtifactsOnExecution(false);
    }

    private boolean awaitPreviousExecutionDrain(String taskId) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXECUTION_DRAIN_TIMEOUT_MILLIS);
        ActiveExecution execution = activeExecutions.get(taskId);
        while (execution != null) {
            if (!execution.hasStarted()) {
                activeExecutions.remove(taskId, execution);
                return true;
            }
            if (System.nanoTime() >= deadlineNanos) {
                return false;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(EXECUTION_DRAIN_POLL_MILLIS));
            execution = activeExecutions.get(taskId);
        }
        return true;
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

    private record ActiveExecution(
            Future<?> future,
            ScheduledFuture<?> timeoutFuture,
            AtomicBoolean started,
            String executionAttemptId
    ) {

        private boolean hasStarted() {
            return started.get();
        }
    }
}
