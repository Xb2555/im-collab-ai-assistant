package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.facade.HarnessFacade;
import com.lark.imcollab.common.facade.TaskUserNotificationFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.planner.config.PlannerExecutionProperties;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.PlannerRetryService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private final PlannerRetryService plannerRetryService = mock(PlannerRetryService.class);
    private final TaskRuntimeService taskRuntimeService = mock(TaskRuntimeService.class);
    private final HarnessFacade harnessFacade = mock(HarnessFacade.class);
    private final PlannerExecutionReviewService reviewService = mock(PlannerExecutionReviewService.class);
    private final TaskUserNotificationFacade notificationFacade = mock(TaskUserNotificationFacade.class);
    private final HoldingAsyncExecutor executor = new HoldingAsyncExecutor();
    private final HoldingScheduledExecutor timeoutScheduler = new HoldingScheduledExecutor();
    private final PlannerExecutionProperties executionProperties = executionProperties();
    private final DefaultImTaskCommandFacade facade = new DefaultImTaskCommandFacade(
            sessionService,
            taskBridgeService,
            plannerRetryService,
            taskRuntimeService,
            harnessFacade,
            reviewService,
            List.of(notificationFacade),
            executor,
            timeoutScheduler,
            executionProperties
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
        verify(taskRuntimeService).syncTaskState("task-1", PlanningPhaseEnum.FAILED);
        verify(taskRuntimeService, never()).projectPhaseTransition(
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

    @Test
    void executionTimeoutMarksTaskFailedAndSkipsReview() {
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

        facade.confirmExecution("task-1");
        timeoutScheduler.runScheduledTasks();

        assertThat(session.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.FAILED);
        assertThat(session.getTransitionReason()).contains("Execution timed out after");
        verify(taskRuntimeService).syncTaskState("task-1", PlanningPhaseEnum.FAILED);
        verify(taskRuntimeService, never()).projectPhaseTransition(
                "task-1",
                PlanningPhaseEnum.FAILED,
                TaskEventTypeEnum.TASK_FAILED
        );
        verify(notificationFacade).notifyExecutionFailed(
                eq(session),
                eq(failedSnapshot),
                contains("Execution timed out after")
        );
        executor.runAll();
        verify(reviewService, never()).reviewAndNotify("task-1");
    }

    @Test
    void retryExecutionResetsFailureAndSubmitsHarnessAgain() {
        PlanTaskSession failed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.FAILED)
                .build();
        PlanTaskSession retrying = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();
        when(sessionService.get("task-1")).thenReturn(failed);
        when(plannerRetryService.isRetryable("task-1", failed)).thenReturn(true);
        when(plannerRetryService.prepareRetry("task-1")).thenReturn(retrying);

        PlanTaskSession returned = facade.retryExecution("task-1");

        assertThat(returned).isEqualTo(retrying);
        verify(taskBridgeService).ensureTask(failed);
        verify(harnessFacade, never()).startExecution("task-1");

        executor.runAll();

        verify(harnessFacade).startExecution("task-1");
        verify(reviewService).reviewAndNotify("task-1");
    }

    @Test
    void retryExecutionKeepsNonFailedTaskUnchanged() {
        PlanTaskSession ready = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        when(sessionService.get("task-1")).thenReturn(ready);
        when(plannerRetryService.isRetryable("task-1", ready)).thenReturn(false);

        PlanTaskSession returned = facade.retryExecution("task-1");

        assertThat(returned).isEqualTo(ready);
        verify(taskBridgeService, never()).ensureTask(any());
        verify(plannerRetryService, never()).prepareRetry("task-1");
        verify(harnessFacade, never()).startExecution("task-1");
    }

    private static PlannerExecutionProperties executionProperties() {
        PlannerExecutionProperties properties = new PlannerExecutionProperties();
        properties.setTimeoutSeconds(1);
        return properties;
    }

    private static class HoldingAsyncExecutor implements AsyncTaskExecutor {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        @Override
        public void execute(Runnable task, long startTimeout) {
            tasks.add(task);
        }

        @Override
        public Future<?> submit(Runnable task) {
            FutureTask<Void> future = new FutureTask<>(task, null);
            tasks.add(future);
            return future;
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            FutureTask<T> future = new FutureTask<>(task);
            tasks.add(future);
            return future;
        }

        void runAll() {
            tasks.forEach(Runnable::run);
            tasks.clear();
        }
    }

    private static class HoldingScheduledExecutor implements ScheduledExecutorService {

        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            tasks.add(command);
            return new ImmediateScheduledFuture();
        }

        void runScheduledTasks() {
            List<Runnable> snapshot = new ArrayList<>(tasks);
            tasks.clear();
            snapshot.forEach(Runnable::run);
        }

        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public <T> Future<T> submit(Callable<T> task) { throw new UnsupportedOperationException(); }
        @Override public <T> Future<T> submit(Runnable task, T result) { throw new UnsupportedOperationException(); }
        @Override public Future<?> submit(Runnable task) { throw new UnsupportedOperationException(); }
        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) { throw new UnsupportedOperationException(); }
        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks) { throw new UnsupportedOperationException(); }
        @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) { throw new UnsupportedOperationException(); }
        @Override public void execute(Runnable command) { throw new UnsupportedOperationException(); }
    }

    private static class ImmediateScheduledFuture implements ScheduledFuture<Object> {

        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return true; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return false; }
        @Override public Object get() throws InterruptedException, ExecutionException { return null; }
        @Override public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException { return null; }
    }
}
