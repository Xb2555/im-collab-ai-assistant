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
                .contains("当前计划要求")
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
