package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionContractFactoryTest {

    private final ExecutionContractFactory factory = new ExecutionContractFactory();

    @Test
    void buildDoesNotDuplicateClarificationAnswersAcrossRepeatedBuilds() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("帮我整理一下，给老板看")
                .clarificationAnswers(List.of("基于飞书项目协作方案整理成文档"))
                .build();

        var first = factory.build(session);
        session.setClarifiedInstruction(first.getClarifiedInstruction());

        var second = factory.build(session);

        assertThat(countOccurrences(second.getClarifiedInstruction(), "基于飞书项目协作方案整理成文档"))
                .isEqualTo(1);
    }

    @Test
    void buildPreservesLatestClarifiedInstructionFromUserIntervention() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("生成客户访谈纪要")
                .clarifiedInstruction("生成客户访谈纪要\n补充说明：请用备用方案重试，先给简版")
                .build();

        var contract = factory.build(session);

        assertThat(contract.getClarifiedInstruction())
                .contains("请用备用方案重试，先给简版");
    }

    @Test
    void buildIncludesCurrentPlanRequirementsForExecutionContext() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("整理飞书项目协作方案")
                .planCards(List.of(
                        UserPlanCard.builder()
                                .cardId("card-001")
                                .title("生成飞书项目协作方案文档")
                                .description("面向老板说明项目目标和进展")
                                .type(PlanCardTypeEnum.DOC)
                                .build(),
                        UserPlanCard.builder()
                                .cardId("card-002")
                                .title("项目风险评估表")
                                .description("识别风险、影响等级和应对措施")
                                .type(PlanCardTypeEnum.DOC)
                                .build()
                ))
                .build();

        var contract = factory.build(session);

        assertThat(contract.getClarifiedInstruction())
                .contains("生成飞书项目协作方案文档 - 面向老板说明项目目标和进展")
                .contains("项目风险评估表 - 识别风险、影响等级和应对措施");
    }

    @Test
    void buildUsesLatestBlueprintRequirementsWhenSessionCardsLagBehind() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("整理飞书项目协作方案")
                .planCards(List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .title("旧文档步骤")
                        .description("旧描述")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .planBlueprint(PlanBlueprint.builder()
                        .planCards(List.of(
                                UserPlanCard.builder()
                                        .cardId("card-001")
                                        .title("生成飞书项目协作方案文档")
                                        .description("面向老板说明项目目标和进展")
                                        .type(PlanCardTypeEnum.DOC)
                                        .build(),
                                UserPlanCard.builder()
                                        .cardId("card-002")
                                        .title("项目风险评估表")
                                        .description("识别风险、影响等级和应对措施")
                                        .type(PlanCardTypeEnum.DOC)
                                        .build()
                        ))
                        .build())
                .build();

        var contract = factory.build(session);

        assertThat(contract.getClarifiedInstruction())
                .contains("生成飞书项目协作方案文档 - 面向老板说明项目目标和进展")
                .contains("项目风险评估表 - 识别风险、影响等级和应对措施")
                .doesNotContain("旧文档步骤 - 旧描述");
    }

    @Test
    void buildUsesMergedPlanAsPrimaryExecutionSemanticsDuringReplan() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("生成 OLD_PLAN_IM_INTERRUPT_20260508 旧计划测试文档")
                .clarifiedInstruction("生成 OLD_PLAN_IM_INTERRUPT_20260508 旧计划测试文档\n补充说明：标题改成 78787")
                .planBlueprint(PlanBlueprint.builder()
                        .taskBrief("生成飞书文档（标题含 78787，6 小节，每节 150 字）")
                        .planCards(List.of(
                                UserPlanCard.builder()
                                        .cardId("card-001")
                                        .title("生成飞书文档（标题含 78787，6 小节，每节 150 字）")
                                        .description("基于用户要求，生成一份飞书文档，标题必须包含 78787，内容为“旧计划测试文档”，分 6 个小节，每节约 150 字，内容随意。")
                                        .type(PlanCardTypeEnum.DOC)
                                        .build()
                        ))
                        .build())
                .build();

        var contract = factory.build(session);

        assertThat(contract.getRawInstruction())
                .contains("标题含 78787")
                .doesNotContain("OLD_PLAN_IM_INTERRUPT_20260508");
        assertThat(contract.getClarifiedInstruction())
                .contains("标题含 78787")
                .contains("标题改成 78787")
                .doesNotContain("OLD_PLAN_IM_INTERRUPT_20260508");
    }

    @Test
    void buildMergesIntentAndCurrentPlanArtifactsSoSummaryIsNotFiltered() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("生成技术方案和 PPT")
                .intentSnapshot(IntentSnapshot.builder()
                        .deliverableTargets(List.of("DOC", "PPT"))
                        .build())
                .planBlueprint(PlanBlueprint.builder()
                        .deliverables(List.of("DOC", "PPT", "SUMMARY"))
                        .planCards(List.of(
                                card("card-001", PlanCardTypeEnum.DOC),
                                card("card-002", PlanCardTypeEnum.PPT),
                                card("card-003", PlanCardTypeEnum.SUMMARY)
                        ))
                        .build())
                .build();

        var contract = factory.build(session);
        PlanBlueprint gated = factory.applyArtifactGate(session.getPlanBlueprint(), contract);

        assertThat(contract.getAllowedArtifacts()).containsExactly("DOC", "PPT", "SUMMARY");
        assertThat(gated.getPlanCards())
                .extracting(UserPlanCard::getType)
                .containsExactly(PlanCardTypeEnum.DOC, PlanCardTypeEnum.PPT, PlanCardTypeEnum.SUMMARY);
    }

    private static UserPlanCard card(String cardId, PlanCardTypeEnum type) {
        return UserPlanCard.builder()
                .cardId(cardId)
                .title(cardId)
                .type(type)
                .dependsOn(List.of())
                .build();
    }

    private static int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while (text != null && pattern != null && !pattern.isEmpty()
                && (index = text.indexOf(pattern, index)) >= 0) {
            count++;
            index += pattern.length();
        }
        return count;
    }
}
