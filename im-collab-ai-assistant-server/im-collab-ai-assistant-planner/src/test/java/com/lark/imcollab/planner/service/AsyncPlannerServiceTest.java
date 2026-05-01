package com.lark.imcollab.planner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEvent;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.planner.config.PlannerAsyncProperties;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.runtime.TaskRuntimeProjectionService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncPlannerServiceTest {

    @Test
    void submitPlanReturnsAcceptedSessionBeforeBackgroundRuns() {
        InMemoryPlannerStateStore store = new InMemoryPlannerStateStore();
        PlannerSessionService sessionService = new PlannerSessionService(store, new PlannerProperties());
        TaskRuntimeProjectionService projectionService = new TaskRuntimeProjectionService(store, new ObjectMapper());
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        HoldingExecutor executor = new HoldingExecutor();
        AsyncPlannerService service = new AsyncPlannerService(
                conversationService,
                sessionService,
                projectionService,
                new PlannerAsyncProperties(),
                executor
        );

        PlanTaskSession accepted = service.submitPlan("写技术方案", null, "task-1", null);

        assertThat(accepted.getTaskId()).isEqualTo("task-1");
        assertThat(accepted.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.INTAKE);
        assertThat(store.task.getStatus().name()).isEqualTo("PLANNING");
        assertThat(store.runtimeEvents)
                .extracting(TaskEventRecord::getType)
                .containsExactly(TaskEventTypeEnum.INTAKE_ACCEPTED);
        assertThat(store.events).isEmpty();
        assertThat(executor.submitted).hasSize(1);
    }

    @Test
    void duplicateSubmitWhileInFlightDoesNotStartSecondBackgroundTask() {
        InMemoryPlannerStateStore store = new InMemoryPlannerStateStore();
        PlannerSessionService sessionService = new PlannerSessionService(store, new PlannerProperties());
        TaskRuntimeProjectionService projectionService = new TaskRuntimeProjectionService(store, new ObjectMapper());
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        HoldingExecutor executor = new HoldingExecutor();
        AsyncPlannerService service = new AsyncPlannerService(
                conversationService,
                sessionService,
                projectionService,
                new PlannerAsyncProperties(),
                executor
        );

        service.submitPlan("写技术方案", null, "task-1", null);
        service.submitPlan("写技术方案", null, "task-1", null);

        assertThat(executor.submitted).hasSize(1);
    }

    @Test
    void backgroundFailureMarksSessionFailed() {
        InMemoryPlannerStateStore store = new InMemoryPlannerStateStore();
        PlannerSessionService sessionService = new PlannerSessionService(store, new PlannerProperties());
        TaskRuntimeProjectionService projectionService = new TaskRuntimeProjectionService(store, new ObjectMapper());
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        when(conversationService.handlePlanRequest("写技术方案", null, "task-1", null))
                .thenThrow(new IllegalStateException("model timeout"));
        HoldingExecutor executor = new HoldingExecutor();
        AsyncPlannerService service = new AsyncPlannerService(
                conversationService,
                sessionService,
                projectionService,
                new PlannerAsyncProperties(),
                executor
        );

        service.submitPlan("写技术方案", null, "task-1", null);
        executor.runAll();

        PlanTaskSession failed = sessionService.get("task-1");
        assertThat(failed.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.FAILED);
        assertThat(store.runtimeEvents)
                .extracting(TaskEventRecord::getType)
                .contains(TaskEventTypeEnum.PLAN_FAILED);
        verify(conversationService).handlePlanRequest("写技术方案", null, "task-1", null);
    }

    private static class HoldingExecutor implements Executor {
        private final List<Runnable> submitted = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            submitted.add(command);
        }

        void runAll() {
            List.copyOf(submitted).forEach(Runnable::run);
        }
    }

    private static class InMemoryPlannerStateStore implements PlannerStateStore {
        private PlanTaskSession session;
        private TaskRecord task;
        private final List<TaskEventRecord> runtimeEvents = new ArrayList<>();
        private final List<TaskEvent> events = new ArrayList<>();

        @Override public void saveSession(PlanTaskSession session) { this.session = session; }
        @Override public Optional<PlanTaskSession> findSession(String taskId) { return Optional.ofNullable(session); }
        @Override public void saveTask(TaskRecord task) { this.task = task; }
        @Override public Optional<TaskRecord> findTask(String taskId) { return Optional.ofNullable(task); }
        @Override public List<TaskRecord> findTasksByOwner(String ownerOpenId, List<com.lark.imcollab.common.model.enums.TaskStatusEnum> statuses, int offset, int limit) { return List.of(); }
        @Override public void appendRuntimeEvent(TaskEventRecord event) { runtimeEvents.add(event); }
        @Override public List<TaskEventRecord> findRuntimeEventsByTaskId(String taskId) { return runtimeEvents; }
        @Override public void appendEvent(TaskEvent event) { events.add(event); }
        @Override public List<String> getEventJsonList(String taskId) { return List.of(); }
        @Override public Optional<String> findConversationTaskId(String conversationKey) { return Optional.empty(); }
        @Override public void saveConversationTaskBinding(String conversationKey, String taskId) { }
        @Override public void saveStep(TaskStepRecord step) { }
        @Override public Optional<TaskStepRecord> findStep(String stepId) { return Optional.empty(); }
        @Override public List<TaskStepRecord> findStepsByTaskId(String taskId) { return List.of(); }
        @Override public void saveArtifact(ArtifactRecord artifact) { }
        @Override public List<ArtifactRecord> findArtifactsByTaskId(String taskId) { return List.of(); }
        @Override public void saveSubmission(TaskSubmissionResult submission) { }
        @Override public Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId) { return Optional.empty(); }
        @Override public void saveEvaluation(TaskResultEvaluation evaluation) { }
        @Override public Optional<TaskResultEvaluation> findEvaluation(String taskId, String agentTaskId) { return Optional.empty(); }
    }
}
