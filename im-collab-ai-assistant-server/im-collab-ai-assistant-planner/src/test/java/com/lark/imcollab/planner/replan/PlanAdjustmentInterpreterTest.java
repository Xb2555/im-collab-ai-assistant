package com.lark.imcollab.planner.replan;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanAdjustmentInterpreterTest {

    private final PlanAdjustmentInterpreter interpreter = new PlanAdjustmentInterpreter(null, new ObjectMapper(), new PlannerProperties());

    @Test
    void removeGroupSummaryKeepsTargetLocalAndSpecific() {
        PlanPatchIntent intent = interpreter.interpret(session(), "我现在不想在群里汇报摘要了", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.REMOVE_STEP);
        assertThat(intent.getTargetCardIds()).containsExactly("card-003");
    }

    @Test
    void addBossPptProducesLocalPatch() {
        PlanPatchIntent intent = interpreter.interpret(session(), "再加上回复老板生成的ppt", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.ADD_STEP);
        assertThat(intent.getNewCardDrafts()).singleElement().satisfies(draft -> {
            assertThat(draft.getType()).isEqualTo(PlanCardTypeEnum.PPT);
            assertThat(draft.getTitle()).contains("老板");
        });
    }

    @Test
    void colloquialRiskTableProducesLocalAddPatch() {
        PlanPatchIntent intent = interpreter.interpret(session(), "顺手补一个风险表", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.ADD_STEP);
        assertThat(intent.getNewCardDrafts()).singleElement().satisfies(draft -> {
            assertThat(draft.getType()).isEqualTo(PlanCardTypeEnum.DOC);
            assertThat(draft.getTitle()).isEqualTo("生成项目风险评估表");
        });
    }

    @Test
    void updateLastStepUsesOrdinalTarget() {
        PlanPatchIntent intent = interpreter.interpret(session(), "把最后一步改成一句话总结", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.UPDATE_STEP);
        assertThat(intent.getTargetCardIds()).containsExactly("card-004");
        assertThat(intent.getNewCardDrafts()).singleElement()
                .extracting(PlanPatchCardDraft::getTitle)
                .isEqualTo("生成一句话总结");
    }

    @Test
    void explicitRegenerateAllUsesFullReplanOperation() {
        PlanPatchIntent intent = interpreter.interpret(session(), "全部重新规划一下", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.REGENERATE_ALL);
    }

    @Test
    void modelCanClassifyOpenEndedNewStepWithoutHardcodedBusinessKeyword() throws Exception {
        ReactAgent agent = mock(ReactAgent.class);
        when(agent.call(anyString(), any())).thenReturn(new AssistantMessage("""
                {"operation":"ADD_STEP","targetCardIds":[],"orderedCardIds":[],"newCardDrafts":[{"title":"补充项目风险评估表","description":"基于当前方案补充风险、影响和缓解措施表","type":"DOC"}],"confidence":0.86,"reason":"user asks to add a risk assessment deliverable","clarificationQuestion":""}
                """));
        PlanAdjustmentInterpreter modelInterpreter = new PlanAdjustmentInterpreter(agent, new ObjectMapper(), new PlannerProperties());

        PlanPatchIntent intent = modelInterpreter.interpret(session(), "再帮我补一个项目风险评估表，列风险、影响和应对措施", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.ADD_STEP);
        assertThat(intent.getNewCardDrafts()).singleElement().satisfies(draft -> {
            assertThat(draft.getTitle()).isEqualTo("补充项目风险评估表");
            assertThat(draft.getType()).isEqualTo(PlanCardTypeEnum.DOC);
        });
    }

    @Test
    void additiveLocalIntentRejectsModelOverwriteOfExistingDoc() throws Exception {
        ReactAgent agent = mock(ReactAgent.class);
        when(agent.call(anyString(), any())).thenReturn(new AssistantMessage("""
                {"operation":"UPDATE_STEP","targetCardIds":["card-001"],"orderedCardIds":[],"newCardDrafts":[{"title":"生成项目风险评估表","description":"基于当前方案补充风险、影响和应对措施表","type":"DOC"}],"confidence":0.92,"reason":"model incorrectly treats additive request as doc update","clarificationQuestion":""}
                """));
        PlanAdjustmentInterpreter modelInterpreter = new PlanAdjustmentInterpreter(agent, new ObjectMapper(), new PlannerProperties());

        PlanPatchIntent intent = modelInterpreter.interpret(session(), "顺手补一个风险表", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.ADD_STEP);
        assertThat(intent.getTargetCardIds()).isNullOrEmpty();
        assertThat(intent.getNewCardDrafts()).singleElement().satisfies(draft -> {
            assertThat(draft.getType()).isEqualTo(PlanCardTypeEnum.DOC);
            assertThat(draft.getTitle()).isEqualTo("生成项目风险评估表");
        });
    }

    private static PlanTaskSession session() {
        return PlanTaskSession.builder()
                .taskId("task-1")
                .planCards(List.of(
                        card("card-001", "生成技术方案文档（含 Mermaid 架构图）", PlanCardTypeEnum.DOC),
                        card("card-002", "生成配套 PPT 初稿", PlanCardTypeEnum.PPT),
                        card("card-003", "生成群内项目进展摘要", PlanCardTypeEnum.SUMMARY),
                        card("card-004", "生成老板汇报 PPT", PlanCardTypeEnum.PPT)
                ))
                .build();
    }

    private static UserPlanCard card(String cardId, String title, PlanCardTypeEnum type) {
        return UserPlanCard.builder()
                .cardId(cardId)
                .title(title)
                .description(title)
                .type(type)
                .status("PENDING")
                .dependsOn(List.of())
                .build();
    }
}
