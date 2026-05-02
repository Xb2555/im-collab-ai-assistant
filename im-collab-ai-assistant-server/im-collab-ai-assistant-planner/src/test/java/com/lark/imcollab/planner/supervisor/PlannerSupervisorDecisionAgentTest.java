package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerSupervisorDecisionAgentTest {

    @Test
    void intakePlanAdjustmentWithExistingPlanIsStateGuarded() {
        PlannerSupervisorDecisionAgent agent = new PlannerSupervisorDecisionAgent(
                null,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new PlannerProperties(),
                null
        );
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .type(PlanCardTypeEnum.DOC)
                        .title("生成文档")
                        .build()))
                .build();

        PlannerSupervisorDecisionResult result = agent.decide(
                session,
                new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "intent classifier selected adjustment"),
                "再加一条：最后输出一段摘要"
        );

        assertThat(result.action()).isEqualTo(PlannerSupervisorAction.PLAN_ADJUSTMENT);
        assertThat(result.confidence()).isEqualTo(1.0d);
    }

    @Test
    void askUserDoesNotForceUnknownMetaQuestionIntoClarificationReply() {
        PlannerSupervisorDecisionAgent agent = new PlannerSupervisorDecisionAgent(
                null,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new PlannerProperties(),
                null
        );
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-ask")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();

        PlannerSupervisorDecisionResult result = agent.decide(
                session,
                new PlannerSupervisorDecision(PlannerSupervisorAction.UNKNOWN, "identity question"),
                "你是谁"
        );

        assertThat(result.action()).isEqualTo(PlannerSupervisorAction.UNKNOWN);
    }

    @Test
    void askUserStillAcceptsExplicitClarificationReply() {
        PlannerSupervisorDecisionAgent agent = new PlannerSupervisorDecisionAgent(
                null,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new PlannerProperties(),
                null
        );
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-ask")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();

        PlannerSupervisorDecisionResult result = agent.decide(
                session,
                new PlannerSupervisorDecision(PlannerSupervisorAction.CLARIFICATION_REPLY, "direct answer"),
                "文档"
        );

        assertThat(result.action()).isEqualTo(PlannerSupervisorAction.CLARIFICATION_REPLY);
        assertThat(result.confidence()).isEqualTo(1.0d);
    }
}
