package com.lark.imcollab.planner.replan;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanAdjustmentInterpreterTest {

    private final PlannerProperties properties = new PlannerProperties();

    @Test
    void modelRemoveGroupSummaryKeepsTargetSpecific() throws Exception {
        PlanPatchIntent intent = modelInterpreter("""
                {"operation":"REMOVE_STEP","targetCardIds":["card-003"],"orderedCardIds":[],"newCardDrafts":[],"confidence":0.92,"reason":"remove the group summary","clarificationQuestion":""}
                """).interpret(session(), "我现在不想在群里汇报摘要了", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.REMOVE_STEP);
        assertThat(intent.getTargetCardIds()).containsExactly("card-003");
    }

    @Test
    void modelAddBossPptProducesPatch() throws Exception {
        PlanPatchIntent intent = modelInterpreter("""
                {"operation":"ADD_STEP","targetCardIds":[],"orderedCardIds":[],"newCardDrafts":[{"title":"生成老板汇报 PPT","description":"基于当前文档生成面向老板汇报的 PPT","type":"PPT"}],"confidence":0.9,"reason":"add boss presentation","clarificationQuestion":""}
                """).interpret(session(), "再加上回复老板生成的ppt", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.ADD_STEP);
        assertThat(intent.getNewCardDrafts()).singleElement().satisfies(draft -> {
            assertThat(draft.getType()).isEqualTo(PlanCardTypeEnum.PPT);
            assertThat(draft.getTitle()).contains("老板");
        });
    }

    @Test
    void modelAlsoNeedBossPptProducesPatch() throws Exception {
        PlanPatchIntent intent = modelInterpreter("""
                {"operation":"ADD_STEP","targetCardIds":[],"orderedCardIds":[],"newCardDrafts":[{"title":"生成老板汇报 PPT","description":"基于当前文档生成面向老板汇报的 PPT","type":"PPT"}],"confidence":0.9,"reason":"add boss presentation","clarificationQuestion":""}
                """).interpret(session(), "还要一个给老板汇报的ppt", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.ADD_STEP);
        assertThat(intent.getNewCardDrafts()).singleElement().satisfies(draft -> {
            assertThat(draft.getType()).isEqualTo(PlanCardTypeEnum.PPT);
            assertThat(draft.getTitle()).contains("老板");
        });
    }

    @Test
    void modelColloquialRiskTableProducesAddPatch() throws Exception {
        PlanPatchIntent intent = modelInterpreter("""
                {"operation":"ADD_STEP","targetCardIds":[],"orderedCardIds":[],"newCardDrafts":[{"title":"生成项目风险评估表","description":"基于当前方案补充风险、影响和应对措施表","type":"DOC"}],"confidence":0.91,"reason":"add risk table","clarificationQuestion":""}
                """).interpret(session(), "顺手补一个风险表", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.ADD_STEP);
        assertThat(intent.getNewCardDrafts()).singleElement().satisfies(draft -> {
            assertThat(draft.getType()).isEqualTo(PlanCardTypeEnum.DOC);
            assertThat(draft.getTitle()).isEqualTo("生成项目风险评估表");
        });
    }

    @Test
    void modelUpdateLastStepUsesExplicitTargetFromAgent() throws Exception {
        PlanPatchIntent intent = modelInterpreter("""
                {"operation":"UPDATE_STEP","targetCardIds":["card-004"],"orderedCardIds":[],"newCardDrafts":[{"title":"生成一句话总结","description":"基于当前计划输出一句话总结","type":"SUMMARY"}],"confidence":0.9,"reason":"update last step","clarificationQuestion":""}
                """).interpret(session(), "把最后一步改成一句话总结", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.UPDATE_STEP);
        assertThat(intent.getTargetCardIds()).containsExactly("card-004");
        assertThat(intent.getNewCardDrafts()).singleElement()
                .extracting(PlanPatchCardDraft::getTitle)
                .isEqualTo("生成一句话总结");
    }

    @Test
    void modelUpdateSummaryStepWithWordingRemovalUsesPatch() throws Exception {
        PlanPatchIntent intent = modelInterpreter("""
                {"operation":"UPDATE_STEP","targetCardIds":["card-003"],"orderedCardIds":[],"newCardDrafts":[{"title":"生成一句话项目进展总结","description":"基于当前计划输出一句话项目进展总结，不提发到群里","type":"SUMMARY"}],"confidence":0.92,"reason":"rewrite the summary step","clarificationQuestion":""}
                """).interpret(session(), "把群里的摘要改成一句话总结，不要提“发到群里”", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.UPDATE_STEP);
        assertThat(intent.getTargetCardIds()).containsExactly("card-003");
        assertThat(intent.getNewCardDrafts()).singleElement().satisfies(draft -> {
            assertThat(draft.getType()).isEqualTo(PlanCardTypeEnum.SUMMARY);
            assertThat(draft.getTitle()).contains("一句话");
            assertThat(draft.getDescription()).contains("不提发到群里");
        });
    }

    @Test
    void modelMergeSeparateSummaryIntoDocUsesMergePatch() throws Exception {
        PlanPatchIntent intent = modelInterpreter("""
                {"operation":"MERGE_STEP","targetCardIds":["card-001","card-003"],"orderedCardIds":[],"newCardDrafts":[{"title":"生成技术方案文档（含落地风险摘要）","description":"生成技术方案文档，并把落地风险摘要放在文档最后，不再单独输出摘要","type":"DOC"}],"confidence":0.93,"reason":"fold separate summary into the document","clarificationQuestion":""}
                """).interpret(session(), "这个摘要不要单独发群，就放在文档最后", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.MERGE_STEP);
        assertThat(intent.getTargetCardIds()).containsExactly("card-001", "card-003");
        assertThat(intent.getNewCardDrafts().get(0).getType()).isEqualTo(PlanCardTypeEnum.DOC);
    }

    @Test
    void modelExplicitRegenerateAllUsesFullReplanOperation() throws Exception {
        PlanPatchIntent intent = modelInterpreter("""
                {"operation":"REGENERATE_ALL","targetCardIds":[],"orderedCardIds":[],"newCardDrafts":[],"confidence":0.95,"reason":"user explicitly requested full replan","clarificationQuestion":""}
                """).interpret(session(), "全部重新规划一下", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.REGENERATE_ALL);
    }

    @Test
    void modelCanClassifyOpenEndedNewStepWithoutHardcodedBusinessKeyword() throws Exception {
        ReactAgent agent = mock(ReactAgent.class);
        when(agent.call(anyString(), any())).thenReturn(new AssistantMessage("""
                {"operation":"ADD_STEP","targetCardIds":[],"orderedCardIds":[],"newCardDrafts":[{"title":"补充项目风险评估表","description":"基于当前方案补充风险、影响和缓解措施表","type":"DOC"}],"confidence":0.86,"reason":"user asks to add a risk assessment deliverable","clarificationQuestion":""}
                """));
        PlanAdjustmentInterpreter modelInterpreter = new PlanAdjustmentInterpreter(
                agent,
                new ObjectMapper(),
                properties,
                new PlannerConversationMemoryService(properties)
        );

        PlanPatchIntent intent = modelInterpreter.interpret(session(), "再帮我补一个项目风险评估表，列风险、影响和应对措施", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.ADD_STEP);
        assertThat(intent.getNewCardDrafts()).singleElement().satisfies(draft -> {
            assertThat(draft.getTitle()).isEqualTo("补充项目风险评估表");
            assertThat(draft.getType()).isEqualTo(PlanCardTypeEnum.DOC);
        });
    }

    @Test
    void additiveRequestDoesNotUseLocalKeywordFallbackWhenModelOverwritesExistingDoc() throws Exception {
        PlanPatchIntent intent = modelInterpreter("""
                {"operation":"UPDATE_STEP","targetCardIds":["card-001"],"orderedCardIds":[],"newCardDrafts":[{"title":"生成项目风险评估表","description":"基于当前方案补充风险、影响和应对措施表","type":"DOC"}],"confidence":0.92,"reason":"model incorrectly treats additive request as doc update","clarificationQuestion":""}
                """).interpret(session(), "顺手补一个风险表", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.UPDATE_STEP);
        assertThat(intent.getTargetCardIds()).containsExactly("card-001");
        assertThat(intent.getReason()).isEqualTo("model incorrectly treats additive request as doc update");
    }

    @Test
    void modelUnavailableClarifiesInsteadOfGuessingWithKeywords() {
        PlanPatchIntent intent = new PlanAdjustmentInterpreter(
                null,
                new ObjectMapper(),
                properties,
                new PlannerConversationMemoryService(properties)
        ).interpret(session(), "顺手补一个风险表", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.CLARIFY_REQUIRED);
        assertThat(intent.getClarificationQuestion()).contains("先不改当前计划");
    }

    @Test
    void invalidModelTargetClarifiesWithoutMutatingPlan() throws Exception {
        PlanPatchIntent intent = modelInterpreter("""
                {"operation":"REMOVE_STEP","targetCardIds":["card-999"],"orderedCardIds":[],"newCardDrafts":[],"confidence":0.9,"reason":"bad target","clarificationQuestion":""}
                """).interpret(session(), "删除那个摘要", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.CLARIFY_REQUIRED);
        assertThat(intent.getClarificationQuestion()).contains("没有对上");
    }

    @Test
    void lowConfidenceModelClarifies() throws Exception {
        PlanPatchIntent intent = modelInterpreter("""
                {"operation":"ADD_STEP","targetCardIds":[],"orderedCardIds":[],"newCardDrafts":[{"title":"生成补充内容","description":"补充内容","type":"SUMMARY"}],"confidence":0.4,"reason":"uncertain","clarificationQuestion":"你想新增什么内容？"}
                """).interpret(session(), "这个也加上", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.CLARIFY_REQUIRED);
        assertThat(intent.getClarificationQuestion()).isEqualTo("你想新增什么内容？");
    }

    @Test
    void reorderRequiresFullCardOrderFromAgent() throws Exception {
        PlanPatchIntent intent = modelInterpreter("""
                {"operation":"REORDER_STEP","targetCardIds":[],"orderedCardIds":["card-002","card-001"],"newCardDrafts":[],"confidence":0.9,"reason":"partial order","clarificationQuestion":""}
                """).interpret(session(), "先做 PPT", null);

        assertThat(intent.getOperation()).isEqualTo(PlanPatchOperation.CLARIFY_REQUIRED);
        assertThat(intent.getClarificationQuestion()).contains("新的步骤顺序");
    }

    private PlanAdjustmentInterpreter modelInterpreter(String response) throws Exception {
        ReactAgent agent = mock(ReactAgent.class);
        when(agent.call(anyString(), any())).thenReturn(new AssistantMessage(response));
        return new PlanAdjustmentInterpreter(
                agent,
                new ObjectMapper(),
                properties,
                new PlannerConversationMemoryService(properties)
        );
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
