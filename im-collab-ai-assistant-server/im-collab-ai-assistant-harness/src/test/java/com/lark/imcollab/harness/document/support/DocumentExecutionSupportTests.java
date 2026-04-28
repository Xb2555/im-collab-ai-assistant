package com.lark.imcollab.harness.document.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.facade.PlannerRuntimeFacade;
import com.lark.imcollab.common.model.entity.AgentTaskPlanCard;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.AgentTaskTypeEnum;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentExecutionSupportTests {

    @Test
    void shouldBackfillSubtaskMetricsFromEvaluation() {
        FakePlannerRuntimeFacade facade = new FakePlannerRuntimeFacade();
        DocumentExecutionSupport support = new DocumentExecutionSupport(facade, new ObjectMapper());

        AgentTaskPlanCard subtask = AgentTaskPlanCard.builder()
                .taskId("card-1:document:generate_outline")
                .taskType(AgentTaskTypeEnum.WRITE_DOC)
                .status("RUNNING")
                .build();

        UserPlanCard card = UserPlanCard.builder()
                .cardId("card-1")
                .agentTaskPlanCards(new ArrayList<>(List.of(subtask)))
                .build();

        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planCards(new ArrayList<>(List.of(card)))
                .build();

        TaskResultEvaluation evaluation = TaskResultEvaluation.builder()
                .taskId("task-1")
                .agentTaskId("card-1:document:generate_outline")
                .resultScore(86)
                .verdict(ResultVerdictEnum.RETRY)
                .build();

        support.updateSubtaskMetrics(session, "card-1", "card-1:document:generate_outline", evaluation, 2);

        assertThat(subtask.getLastResultScore()).isEqualTo(86);
        assertThat(subtask.getLastVerdict()).isEqualTo("RETRY");
        assertThat(subtask.getRetryCount()).isEqualTo(2);
        assertThat(facade.savedSessions).contains(session);
    }

    @Test
    void shouldCreateSectionSubtaskWithStableNaming() {
        FakePlannerRuntimeFacade facade = new FakePlannerRuntimeFacade();
        DocumentExecutionSupport support = new DocumentExecutionSupport(facade, new ObjectMapper());

        UserPlanCard card = UserPlanCard.builder()
                .cardId("card-2")
                .agentTaskPlanCards(new ArrayList<>())
                .build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-2")
                .planCards(new ArrayList<>(List.of(card)))
                .build();

        support.ensureSectionTask(session, card, "背景", "背景");

        assertThat(card.getAgentTaskPlanCards()).hasSize(1);
        AgentTaskPlanCard sectionTask = card.getAgentTaskPlanCards().get(0);
        assertThat(sectionTask.getTaskId()).isEqualTo("card-2:document:generate_section:背景");
        assertThat(sectionTask.getContext()).isEqualTo("document:generate_section:背景");
        assertThat(sectionTask.getInput()).isEqualTo("背景");
        assertThat(facade.savedSessions).contains(session);
    }

    private static final class FakePlannerRuntimeFacade implements PlannerRuntimeFacade {
        private final List<PlanTaskSession> savedSessions = new ArrayList<>();

        @Override
        public PlanTaskSession getSession(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PlanTaskSession saveSession(PlanTaskSession session) {
            savedSessions.add(session);
            return session;
        }

        @Override
        public void publishEvent(String taskId, String status) {
        }

        @Override
        public void publishEvent(String taskId, String status, RequireInput requireInput) {
        }

        @Override
        public TaskResultEvaluation evaluate(TaskSubmissionResult submission) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId) {
            return Optional.empty();
        }
    }
}
