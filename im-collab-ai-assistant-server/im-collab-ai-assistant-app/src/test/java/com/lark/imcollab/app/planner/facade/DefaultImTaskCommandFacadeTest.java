package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.facade.HarnessFacade;
import com.lark.imcollab.common.facade.TaskUserNotificationFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRecord;
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
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultImTaskCommandFacadeTest {

    private final PlannerSessionService sessionService = mock(PlannerSessionService.class);
    private final TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
    private final PlannerRetryService plannerRetryService = mock(PlannerRetryService.class);
    private final TaskRuntimeService taskRuntimeService = mock(TaskRuntimeService.class);
    private final HarnessFacade harnessFacade = mock(HarnessFacade.class);
    private final PlannerExecutionReviewService reviewService = mock(PlannerExecutionReviewService.class);
    private final TaskArtifactResetService taskArtifactResetService = mock(TaskArtifactResetService.class);
    private final TaskUserNotificationFacade notificationFacade = mock(TaskUserNotificationFacade.class);
    private final HoldingAsyncExecutor executor = new HoldingAsyncExecutor();
    private final HoldingScheduledExecutor timeoutScheduler = new HoldingScheduledExecutor();
    private final PlannerExecutionProperties executionProperties = executionProperties();
    private final TaskCancellationRegistry cancellationRegistry = new TaskCancellationRegistry();
    private final DefaultImTaskCommandFacade facade = new DefaultImTaskCommandFacade(
            sessionService,
            taskBridgeService,
            plannerRetryService,
            taskRuntimeService,
            harnessFacade,
            reviewService,
            taskArtifactResetService,
            List.of(notificationFacade),
            executor,
            timeoutScheduler,
            executionProperties,
            cancellationRegistry
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
        verify(taskArtifactResetService).clearGeneratedArtifactsBeforeExecution("task-1");
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
        when(plannerRetryService.prepareRetry("task-1", "请用备用方案重试")).thenReturn(retrying);

        PlanTaskSession returned = facade.retryExecution("task-1", "请用备用方案重试");

        assertThat(returned).isEqualTo(retrying);
        verify(taskArtifactResetService).clearGeneratedArtifactsBeforeExecution("task-1");
        verify(taskBridgeService).ensureTask(retrying);
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

        PlanTaskSession returned = facade.retryExecution("task-1", "请用备用方案重试");

        assertThat(returned).isEqualTo(ready);
        verify(taskBridgeService, never()).ensureTask(any());
        verify(plannerRetryService, never()).prepareRetry("task-1", "请用备用方案重试");
        verify(harnessFacade, never()).startExecution("task-1");
    }

    @Test
    void cancelExecutionInterruptsQueuedHarnessAndAbortsDomainTask() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        when(sessionService.get("task-1")).thenReturn(session);

        facade.confirmExecution("task-1");
        facade.cancelExecution("task-1");
        verify(harnessFacade, never()).abortExecution("task-1");
        executor.runAll();

        assertThat(cancellationRegistry.isCancelled("task-1")).isTrue();
        verify(harnessFacade).abortExecution("task-1");
        verify(harnessFacade, never()).startExecution("task-1");
        verify(reviewService, never()).reviewAndNotify("task-1");
    }

    @Test
    void confirmExecutionWaitsForCancelledRunningExecutionToDrainBeforeRestarting() throws Exception {
        PlannerExecutionProperties properties = executionProperties();
        DirectAsyncExecutor directExecutor = new DirectAsyncExecutor();
        HoldingScheduledExecutor scheduledExecutor = new HoldingScheduledExecutor();
        TaskCancellationRegistry registry = new TaskCancellationRegistry();
        DefaultImTaskCommandFacade blockingFacade = new DefaultImTaskCommandFacade(
                sessionService,
                taskBridgeService,
                plannerRetryService,
                taskRuntimeService,
                harnessFacade,
                reviewService,
                taskArtifactResetService,
                List.of(notificationFacade),
                directExecutor,
                scheduledExecutor,
                properties,
                registry
        );
        PlanTaskSession first = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        PlanTaskSession second = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        when(sessionService.get("task-1")).thenReturn(first, second);

        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean inFlight = new AtomicBoolean(false);
        AtomicInteger starts = new AtomicInteger();
        when(harnessFacade.startExecution("task-1")).thenAnswer(invocation -> {
            if (!inFlight.compareAndSet(false, true)) {
                throw new IllegalStateException("Task is already executing");
            }
            starts.incrementAndGet();
            running.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.set(false);
            }
            return null;
        });

        blockingFacade.confirmExecution("task-1");
        assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();

        blockingFacade.cancelExecution("task-1");

        Thread restart = new Thread(() -> blockingFacade.confirmExecution("task-1"));
        restart.start();
        assertEventuallyEquals(starts, 1, 500);

        release.countDown();
        restart.join(2_000L);

        assertEventuallyEquals(starts, 2, 2_000);
        verify(harnessFacade, times(2)).startExecution("task-1");
    }

    private static void assertEventuallyEquals(AtomicInteger value, int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (value.get() == expected) {
                return;
            }
            Thread.sleep(10L);
        }
        assertThat(value.get()).isEqualTo(expected);
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

    private static class DirectAsyncExecutor implements AsyncTaskExecutor {

        private final ExecutorService executorService = Executors.newCachedThreadPool();

        @Override
        public void execute(Runnable command) {
            executorService.execute(command);
        }

        @Override
        public void execute(Runnable task, long startTimeout) {
            executorService.execute(task);
        }

        @Override
        public Future<?> submit(Runnable task) {
            return executorService.submit(task);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return executorService.submit(task);
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
