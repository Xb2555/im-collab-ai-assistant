package com.lark.imcollab.harness.document.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.facade.PlannerRuntimeFacade;
import com.lark.imcollab.common.model.entity.AgentTaskPlanCard;
import com.lark.imcollab.common.model.entity.DocumentOutline;
import com.lark.imcollab.common.model.entity.DocumentOutlineSection;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.AgentTaskTypeEnum;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import com.lark.imcollab.harness.document.support.DocumentExecutionSupport;
import com.lark.imcollab.harness.document.template.DocumentTemplateService;
import com.lark.imcollab.harness.document.workflow.DocumentStateKeys;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentWorkflowNodesTests {

    @Test
    void shouldExecuteSectionsOneByOneAndTrackCompletedSectionKeys() {
        FakePlannerRuntimeFacade facade = new FakePlannerRuntimeFacade();
        ObjectMapper objectMapper = new ObjectMapper();
        PlanTaskSession session = buildSession();
        facade.session = session;
        facade.evaluations.add(TaskResultEvaluation.builder().verdict(ResultVerdictEnum.PASS).resultScore(91).build());
        facade.evaluations.add(TaskResultEvaluation.builder().verdict(ResultVerdictEnum.PASS).resultScore(93).build());

        TestDocumentWorkflowNodes nodes = new TestDocumentWorkflowNodes(
                new DocumentExecutionSupport(facade, objectMapper),
                objectMapper,
                "{\"heading\":\"背景\",\"body\":\"背景正文\"}",
                "{\"heading\":\"方案\",\"body\":\"方案正文\"}"
        );

        DocumentOutline outline = DocumentOutline.builder()
                .title("场景C文档")
                .sections(List.of(
                        DocumentOutlineSection.builder().heading("背景").keyPoints(List.of("现状")).build(),
                        DocumentOutlineSection.builder().heading("方案").keyPoints(List.of("路径")).build()
                ))
                .build();

        OverAllState state = new OverAllState(new HashMap<>(Map.of(
                DocumentStateKeys.TASK_ID, "task-1",
                DocumentStateKeys.CARD_ID, "card-1",
                DocumentStateKeys.OUTLINE, outline
        )));

        Map<String, Object> result = nodes.generateSections(state, RunnableConfig.builder().threadId("t").build()).join();

        assertThat(result.get(DocumentStateKeys.DONE_SECTIONS)).isEqualTo(true);
        assertThat(result.get(DocumentStateKeys.COMPLETED_SECTION_KEYS)).isEqualTo(List.of("背景", "方案"));
        assertThat(facade.submissions).extracting(TaskSubmissionResult::getAgentTaskId)
                .containsExactly("card-1:document:generate_section:背景", "card-1:document:generate_section:方案");
        assertThat(findSubtask(session, "card-1:document:generate_sections").getStatus()).isEqualTo("COMPLETED");
        assertThat(findSubtask(session, "card-1:document:generate_section:背景").getStatus()).isEqualTo("COMPLETED");
        assertThat(findSubtask(session, "card-1:document:generate_section:方案").getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void shouldFailSectionGenerationGracefullyWhenRetryExhausted() {
        FakePlannerRuntimeFacade facade = new FakePlannerRuntimeFacade();
        ObjectMapper objectMapper = new ObjectMapper();
        PlanTaskSession session = buildSession();
        facade.session = session;
        facade.evaluations.add(TaskResultEvaluation.builder().verdict(ResultVerdictEnum.PASS).resultScore(90).build());
        facade.evaluations.add(TaskResultEvaluation.builder().verdict(ResultVerdictEnum.RETRY).resultScore(40).issues(List.of("证据不足")).build());
        facade.evaluations.add(TaskResultEvaluation.builder().verdict(ResultVerdictEnum.RETRY).resultScore(41).issues(List.of("证据不足")).build());
        facade.evaluations.add(TaskResultEvaluation.builder().verdict(ResultVerdictEnum.RETRY).resultScore(42).issues(List.of("证据不足")).build());

        TestDocumentWorkflowNodes nodes = new TestDocumentWorkflowNodes(
                new DocumentExecutionSupport(facade, objectMapper),
                objectMapper,
                "{\"heading\":\"背景\",\"body\":\"背景正文\"}",
                "{\"heading\":\"方案\",\"body\":\"方案正文-第一次\"}",
                "{\"heading\":\"方案\",\"body\":\"方案正文-第二次\"}",
                "{\"heading\":\"方案\",\"body\":\"方案正文-第三次\"}"
        );

        DocumentOutline outline = DocumentOutline.builder()
                .title("场景C文档")
                .sections(List.of(
                        DocumentOutlineSection.builder().heading("背景").keyPoints(List.of("现状")).build(),
                        DocumentOutlineSection.builder().heading("方案").keyPoints(List.of("路径")).build()
                ))
                .build();

        OverAllState state = new OverAllState(new HashMap<>(Map.of(
                DocumentStateKeys.TASK_ID, "task-1",
                DocumentStateKeys.CARD_ID, "card-1",
                DocumentStateKeys.OUTLINE, outline
        )));

        Map<String, Object> result = nodes.generateSections(state, RunnableConfig.builder().threadId("t").build()).join();

        assertThat(result.get(DocumentStateKeys.WAITING_HUMAN_REVIEW)).isEqualTo(false);
        assertThat(result.get(DocumentStateKeys.HALTED_STAGE)).isEqualTo(DocumentExecutionSupport.SECTIONS_TASK_SUFFIX);
        assertThat(result.get(DocumentStateKeys.COMPLETED_SECTION_KEYS)).isEqualTo(List.of("背景"));
        assertThat(session.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.FAILED);
        assertThat(session.getTransitionReason()).isEqualTo("Document stage failed: generate_sections");
        assertThat(findCard(session).getStatus()).isEqualTo("FAILED");
        assertThat(findSubtask(session, "card-1:document:generate_sections").getStatus()).isEqualTo("FAILED");
        assertThat(findSubtask(session, "card-1:document:generate_section:背景").getStatus()).isEqualTo("COMPLETED");
        assertThat(findSubtask(session, "card-1:document:generate_section:方案").getStatus()).isEqualTo("FAILED");
        assertThat(facade.publishedStatuses).contains("SECTIONS_GENERATING", "FAILED");
    }

    private static PlanTaskSession buildSession() {
        UserPlanCard card = UserPlanCard.builder()
                .cardId("card-1")
                .taskId("task-1")
                .title("文档生成")
                .description("生成场景C文档")
                .type(PlanCardTypeEnum.DOC)
                .status("PENDING")
                .agentTaskPlanCards(new ArrayList<>(List.of(
                        task("card-1:document:generate_outline"),
                        task("card-1:document:generate_sections"),
                        task("card-1:document:review_doc"),
                        task("card-1:document:write_doc_and_sync")
                )))
                .build();
        return PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .audience("团队")
                .tone("正式")
                .language("zh-CN")
                .planCards(new ArrayList<>(List.of(card)))
                .build();
    }

    private static AgentTaskPlanCard task(String taskId) {
        return AgentTaskPlanCard.builder()
                .taskId(taskId)
                .taskType(AgentTaskTypeEnum.WRITE_DOC)
                .status("PENDING")
                .build();
    }

    private static UserPlanCard findCard(PlanTaskSession session) {
        return session.getPlanCards().get(0);
    }

    private static AgentTaskPlanCard findSubtask(PlanTaskSession session, String taskId) {
        return findCard(session).getAgentTaskPlanCards().stream()
                .filter(item -> taskId.equals(item.getTaskId()))
                .findFirst()
                .orElseThrow();
    }

    private static final class TestDocumentWorkflowNodes extends DocumentWorkflowNodes {
        private final Deque<String> sectionResponses;

        private TestDocumentWorkflowNodes(
                DocumentExecutionSupport executionSupport,
                ObjectMapper objectMapper,
                String... sectionResponses) {
            super(
                    executionSupport,
                    new DocumentTemplateService(),
                    null,
                    null,
                    null,
                    new LarkDocTool(null),
                    objectMapper
            );
            this.sectionResponses = new ArrayDeque<>(List.of(sectionResponses));
        }

        @Override
        protected AssistantMessage callAgent(ReactAgent agent, String prompt, String threadId) {
            if (threadId.contains(":section:")) {
                return new AssistantMessage(sectionResponses.removeFirst());
            }
            throw new UnsupportedOperationException(threadId);
        }
    }

    private static final class FakePlannerRuntimeFacade implements PlannerRuntimeFacade {
        private PlanTaskSession session;
        private final Deque<TaskResultEvaluation> evaluations = new ArrayDeque<>();
        private final List<TaskSubmissionResult> submissions = new ArrayList<>();
        private final List<String> publishedStatuses = new ArrayList<>();

        @Override
        public PlanTaskSession getSession(String taskId) {
            return session;
        }

        @Override
        public PlanTaskSession saveSession(PlanTaskSession session) {
            this.session = session;
            return session;
        }

        @Override
        public void publishEvent(String taskId, String status) {
            publishedStatuses.add(status);
        }

        @Override
        public void publishEvent(String taskId, String status, RequireInput requireInput) {
            publishedStatuses.add(status);
        }

        @Override
        public TaskResultEvaluation evaluate(TaskSubmissionResult submission) {
            submissions.add(submission);
            TaskResultEvaluation evaluation = evaluations.removeFirst();
            return TaskResultEvaluation.builder()
                    .taskId(submission.getTaskId())
                    .agentTaskId(submission.getAgentTaskId())
                    .resultScore(evaluation.getResultScore())
                    .verdict(evaluation.getVerdict())
                    .issues(evaluation.getIssues())
                    .suggestions(evaluation.getSuggestions())
                    .build();
        }

        @Override
        public Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId) {
            return Optional.empty();
        }
    }
}
