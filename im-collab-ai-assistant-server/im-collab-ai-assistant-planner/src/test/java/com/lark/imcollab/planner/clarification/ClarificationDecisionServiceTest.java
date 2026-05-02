package com.lark.imcollab.planner.clarification;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClarificationDecisionServiceTest {

    private final ReactAgent clarificationAgent = mock(ReactAgent.class);
    private final PlannerProperties properties = new PlannerProperties();
    private final ClarificationDecisionService service = new ClarificationDecisionService(
            clarificationAgent,
            new ObjectMapper(),
            properties,
            new PlannerConversationMemoryService(properties)
    );

    @Test
    void askUserDecisionKeepsOnlyThreeQuestions() throws Exception {
        when(clarificationAgent.call(anyString(), any(RunnableConfig.class)))
                .thenReturn(new AssistantMessage("""
                        {"action":"ASK_USER","questions":["问题1","问题2","问题3","问题4"],"intentSummary":"","confidence":0.9,"reason":"missing scope"}
                        """));

        ClarificationDecision decision = service.decide(session(), "帮我整理一下", null);

        assertThat(decision.action()).isEqualTo(ClarificationAction.ASK_USER);
        assertThat(decision.questions()).containsExactly("问题1", "问题2", "问题3");
    }

    @Test
    void readyDecisionCarriesIntentSummary() throws Exception {
        when(clarificationAgent.call(anyString(), any(RunnableConfig.class)))
                .thenReturn(new AssistantMessage("""
                        {"action":"READY","questions":[],"intentSummary":"生成一份技术方案文档","confidence":0.86,"reason":"clear task"}
                        """));

        ClarificationDecision decision = service.decide(session(), "生成一份技术方案文档", null);

        assertThat(decision.action()).isEqualTo(ClarificationAction.READY);
        assertThat(decision.planningInput("fallback")).isEqualTo("生成一份技术方案文档");
    }

    @Test
    void invalidJsonFallsBackToSafeClarification() throws Exception {
        when(clarificationAgent.call(anyString(), any(RunnableConfig.class)))
                .thenReturn(new AssistantMessage("not json"));

        ClarificationDecision decision = service.decide(session(), "做个东西", null);

        assertThat(decision.action()).isEqualTo(ClarificationAction.ASK_USER);
        assertThat(decision.questions()).hasSize(1);
        assertThat(decision.questions().get(0)).startsWith("你希望");
    }

    @Test
    void lowConfidenceDoesNotProceedToPlanning() throws Exception {
        when(clarificationAgent.call(anyString(), any(RunnableConfig.class)))
                .thenReturn(new AssistantMessage("""
                        {"action":"READY","questions":[],"intentSummary":"随便做一下","confidence":0.3,"reason":"uncertain"}
                        """));

        ClarificationDecision decision = service.decide(session(), "随便做一下", null);

        assertThat(decision.action()).isEqualTo(ClarificationAction.ASK_USER);
    }

    private PlanTaskSession session() {
        return PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build();
    }
}
