package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.AgentTaskPlanCard;
import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEvent;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.exception.VersionConflictException;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.springframework.beans.BeanUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlannerSessionServiceTest {

    @Test
    void internalSaveDoesNotAdvanceUserVisibleVersion() {
        PlannerSessionService service = new PlannerSessionService(new InMemoryStore(), new PlannerProperties());
        PlanTaskSession session = service.getOrCreate("task-1");

        service.saveWithoutVersionChange(session);
        assertThat(session.getVersion()).isZero();
        assertThat(session.getStateRevision()).isEqualTo(1);

        service.save(session);
        assertThat(session.getVersion()).isEqualTo(1);
        assertThat(session.getStateRevision()).isEqualTo(2);

        service.saveWithoutVersionChange(session);
        assertThat(session.getVersion()).isEqualTo(1);
        assertThat(session.getStateRevision()).isEqualTo(3);
    }

    @Test
    void staleSessionSaveFailsWhenStateRevisionHasMoved() {
        InMemoryStore store = new InMemoryStore();
        PlannerSessionService service = new PlannerSessionService(store, new PlannerProperties());
        PlanTaskSession first = service.getOrCreate("task-1");
        PlanTaskSession stale = service.get("task-1");

        service.saveWithoutVersionChange(first);

        assertThatThrownBy(() -> service.saveWithoutVersionChange(stale))
                .isInstanceOf(VersionConflictException.class)
                .hasMessageContaining("expectedStateRevision=0");
        assertThat(stale.getStateRevision()).isZero();
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

    @Test
    void publishEventOverlaysRuntimeStepStatusForStreamSubtasks() {
        InMemoryStore store = new InMemoryStore();
        PlannerSessionService service = new PlannerSessionService(store, new PlannerProperties());
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .version(3)
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .type(PlanCardTypeEnum.DOC)
                        .title("生成文档")
                        .agentTaskPlanCards(List.of(AgentTaskPlanCard.builder()
                                .id("task-001")
                                .parentCardId("card-001")
                                .title("生成文档")
                                .status("pending")
                                .build()))
                        .build()))
                .build();
        store.saveSession(session);
        store.steps.add(TaskStepRecord.builder()
                .taskId("task-1")
                .stepId("card-001")
                .status(StepStatusEnum.COMPLETED)
                .outputSummary("done")
                .retryCount(2)
                .build());

        service.publishEvent("task-1", "COMPLETED");

        TaskEvent event = store.events.get(0);
        assertThat(event.getSubtasks()).hasSize(1);
        assertThat(event.getSubtasks().get(0).getStatus()).isEqualTo("completed");
        assertThat(event.getSubtasks().get(0).getOutput()).isEqualTo("done");
        assertThat(event.getSubtasks().get(0).getRetryCount()).isEqualTo(2);
    }

    private static class InMemoryStore implements PlannerStateStore {
        private final Map<String, PlanTaskSession> sessions = new HashMap<>();
        private final List<TaskEvent> events = new ArrayList<>();
        private final List<TaskStepRecord> steps = new ArrayList<>();

        @Override
        public void saveSession(PlanTaskSession session) {
            sessions.put(session.getTaskId(), copy(session));
        }

        @Override
        public boolean saveSessionIfStateRevision(PlanTaskSession session, long expectedStateRevision) {
            PlanTaskSession current = sessions.get(session.getTaskId());
            if (current == null) {
                if (expectedStateRevision != 0L) {
                    return false;
                }
                sessions.put(session.getTaskId(), copy(session));
                return true;
            }
            if (current.getStateRevision() != expectedStateRevision) {
                return false;
            }
            sessions.put(session.getTaskId(), copy(session));
            return true;
        }

        @Override
        public Optional<PlanTaskSession> findSession(String taskId) {
            return Optional.ofNullable(sessions.get(taskId)).map(InMemoryStore::copy);
        }

        @Override public Optional<String> findConversationTaskId(String conversationKey) { return Optional.empty(); }
        @Override public void saveConversationTaskBinding(String conversationKey, String taskId) {}
        @Override public void appendEvent(TaskEvent event) { events.add(event); }
        @Override public List<String> getEventJsonList(String taskId) { return List.of(); }
        @Override public void saveTask(TaskRecord task) {}
        @Override public Optional<TaskRecord> findTask(String taskId) { return Optional.empty(); }
        @Override public List<TaskRecord> findTasksByOwner(String ownerOpenId, List<com.lark.imcollab.common.model.enums.TaskStatusEnum> statuses, int offset, int limit) { return List.of(); }
        @Override public void saveStep(TaskStepRecord step) {}
        @Override public List<TaskStepRecord> findStepsByTaskId(String taskId) {
            return steps.stream().filter(step -> taskId.equals(step.getTaskId())).toList();
        }
        @Override public Optional<TaskStepRecord> findStep(String stepId) { return Optional.empty(); }
        @Override public void saveArtifact(ArtifactRecord artifact) {}
        @Override public List<ArtifactRecord> findArtifactsByTaskId(String taskId) { return List.of(); }
        @Override public void appendRuntimeEvent(TaskEventRecord event) {}
        @Override public List<TaskEventRecord> findRuntimeEventsByTaskId(String taskId) { return List.of(); }
        @Override public void saveSubmission(TaskSubmissionResult submission) {}
        @Override public Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId) { return Optional.empty(); }
        @Override public void saveEvaluation(TaskResultEvaluation evaluation) {}
        @Override public Optional<TaskResultEvaluation> findEvaluation(String taskId, String agentTaskId) { return Optional.empty(); }

        private static PlanTaskSession copy(PlanTaskSession session) {
            PlanTaskSession copy = new PlanTaskSession();
            BeanUtils.copyProperties(session, copy);
            return copy;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<ScenarioCodeEnum> rawScenarioPath(String... values) {
        return (List) List.of(values);
    }
}
