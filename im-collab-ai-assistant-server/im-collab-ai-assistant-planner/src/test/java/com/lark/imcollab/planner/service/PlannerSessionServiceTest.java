package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEvent;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerSessionServiceTest {

    @Test
    void internalSaveDoesNotAdvanceUserVisibleVersion() {
        PlannerSessionService service = new PlannerSessionService(new InMemoryStore(), new PlannerProperties());
        PlanTaskSession session = service.getOrCreate("task-1");

        service.saveWithoutVersionChange(session);
        assertThat(session.getVersion()).isZero();

        service.save(session);
        assertThat(session.getVersion()).isEqualTo(1);

        service.saveWithoutVersionChange(session);
        assertThat(session.getVersion()).isEqualTo(1);
    }

    @Test
    void normalizesStringScenarioPathWhenReadingAndSavingSession() {
        InMemoryStore store = new InMemoryStore();
        PlannerSessionService service = new PlannerSessionService(store, new PlannerProperties());
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .scenarioPath(rawScenarioPath("A_IM", "B_PLANNING"))
                .intentSnapshot(IntentSnapshot.builder()
                        .scenarioPath(rawScenarioPath("C_DOC"))
                        .build())
                .planBlueprint(PlanBlueprint.builder()
                        .scenarioPath(rawScenarioPath("D_PRESENTATION"))
                        .build())
                .build();
        store.saveSession(session);

        PlanTaskSession result = service.get("task-1");

        assertThat(result.getScenarioPath()).containsExactly(ScenarioCodeEnum.A_IM, ScenarioCodeEnum.B_PLANNING);
        assertThat(result.getIntentSnapshot().getScenarioPath()).containsExactly(ScenarioCodeEnum.C_DOC);
        assertThat(result.getPlanBlueprint().getScenarioPath()).containsExactly(ScenarioCodeEnum.D_PRESENTATION);

        service.saveWithoutVersionChange(result);
        assertThat(store.findSession("task-1").orElseThrow().getIntentSnapshot().getScenarioPath().get(0))
                .isInstanceOf(ScenarioCodeEnum.class);
    }

    private static class InMemoryStore implements PlannerStateStore {
        private final Map<String, PlanTaskSession> sessions = new HashMap<>();

        @Override
        public void saveSession(PlanTaskSession session) {
            sessions.put(session.getTaskId(), session);
        }

        @Override
        public Optional<PlanTaskSession> findSession(String taskId) {
            return Optional.ofNullable(sessions.get(taskId));
        }

        @Override public Optional<String> findConversationTaskId(String conversationKey) { return Optional.empty(); }
        @Override public void saveConversationTaskBinding(String conversationKey, String taskId) {}
        @Override public void appendEvent(TaskEvent event) {}
        @Override public List<String> getEventJsonList(String taskId) { return List.of(); }
        @Override public void saveTask(TaskRecord task) {}
        @Override public Optional<TaskRecord> findTask(String taskId) { return Optional.empty(); }
        @Override public List<TaskRecord> findTasksByOwner(String ownerOpenId, List<com.lark.imcollab.common.model.enums.TaskStatusEnum> statuses, int offset, int limit) { return List.of(); }
        @Override public void saveStep(TaskStepRecord step) {}
        @Override public List<TaskStepRecord> findStepsByTaskId(String taskId) { return List.of(); }
        @Override public Optional<TaskStepRecord> findStep(String stepId) { return Optional.empty(); }
        @Override public void saveArtifact(ArtifactRecord artifact) {}
        @Override public List<ArtifactRecord> findArtifactsByTaskId(String taskId) { return List.of(); }
        @Override public void appendRuntimeEvent(TaskEventRecord event) {}
        @Override public List<TaskEventRecord> findRuntimeEventsByTaskId(String taskId) { return List.of(); }
        @Override public void saveSubmission(TaskSubmissionResult submission) {}
        @Override public Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId) { return Optional.empty(); }
        @Override public void saveEvaluation(TaskResultEvaluation evaluation) {}
        @Override public Optional<TaskResultEvaluation> findEvaluation(String taskId, String agentTaskId) { return Optional.empty(); }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<ScenarioCodeEnum> rawScenarioPath(String... values) {
        return (List) List.of(values);
    }
}
