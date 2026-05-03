package com.lark.imcollab.planner.replan;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CardPlanPatchMergerTest {

    private final CardPlanPatchMerger merger = new CardPlanPatchMerger();

    @Test
    void removeSummaryPreservesOtherCardsAndRepairsDependencies() {
        PlanBlueprint result = merger.merge(blueprint(), PlanPatchIntent.builder()
                .operation(PlanPatchOperation.REMOVE_STEP)
                .targetCardIds(List.of("card-003"))
                .confidence(0.9d)
                .build(), "task-1");

        assertThat(result.getPlanCards())
                .extracting(UserPlanCard::getTitle)
                .containsExactly("生成技术方案文档", "生成配套 PPT", "生成老板汇报 PPT");
        assertThat(result.getPlanCards().get(2).getDependsOn()).containsExactly("card-002");
        assertThat(result.getDeliverables()).containsExactly("DOC", "PPT");
    }

    @Test
    void addStepUsesNextCardIdAndPreservesExistingPlan() {
        PlanBlueprint result = merger.merge(blueprint(), PlanPatchIntent.builder()
                .operation(PlanPatchOperation.ADD_STEP)
                .newCardDrafts(List.of(PlanPatchCardDraft.builder()
                        .title("生成一句话总结")
                        .type(PlanCardTypeEnum.SUMMARY)
                        .build()))
                .confidence(0.9d)
                .build(), "task-1");

        assertThat(result.getPlanCards())
                .extracting(UserPlanCard::getCardId)
                .doesNotHaveDuplicates()
                .contains("card-005");
        assertThat(result.getPlanCards())
                .extracting(UserPlanCard::getTitle)
                .contains("生成技术方案文档", "生成配套 PPT", "生成群内项目进展摘要", "生成老板汇报 PPT", "生成一句话总结");
    }

    @Test
    void duplicateCardIdsAreNormalizedBeforeGate() {
        PlanBlueprint source = PlanBlueprint.builder()
                .planCards(List.of(
                        card("card-001", "生成技术方案文档", PlanCardTypeEnum.DOC, List.of()),
                        card("card-001", "生成配套 PPT", PlanCardTypeEnum.PPT, List.of("card-001"))
                ))
                .build();

        PlanBlueprint result = merger.merge(source, PlanPatchIntent.builder()
                .operation(PlanPatchOperation.ADD_STEP)
                .newCardDrafts(List.of(PlanPatchCardDraft.builder()
                        .title("生成补充摘要")
                        .type(PlanCardTypeEnum.SUMMARY)
                        .build()))
                .confidence(0.9d)
                .build(), "task-1");

        assertThat(result.getPlanCards())
                .extracting(UserPlanCard::getCardId)
                .doesNotHaveDuplicates();
    }

    @Test
    void mergeStepUpdatesDestinationAndRemovesSeparateSourceStep() {
        PlanBlueprint result = merger.merge(blueprint(), PlanPatchIntent.builder()
                .operation(PlanPatchOperation.MERGE_STEP)
                .targetCardIds(List.of("card-001", "card-003"))
                .newCardDrafts(List.of(PlanPatchCardDraft.builder()
                        .title("生成技术方案文档（含项目进展摘要）")
                        .description("生成技术方案文档，并把项目进展摘要放在文档最后，不再单独输出摘要")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .confidence(0.9d)
                .build(), "task-1");

        assertThat(result.getPlanCards())
                .extracting(UserPlanCard::getTitle)
                .containsExactly("生成技术方案文档（含项目进展摘要）", "生成配套 PPT", "生成老板汇报 PPT");
        assertThat(result.getDeliverables()).containsExactly("DOC", "PPT");
        assertThat(result.getPlanCards().get(2).getDependsOn()).containsExactly("card-002");
    }

    @Test
    void updateStepRefreshesDependentDescriptionReferences() {
        PlanBlueprint source = PlanBlueprint.builder()
                .planCards(List.of(
                        card("card-001", "生成竞品分析与销售应答话术文档", PlanCardTypeEnum.DOC, List.of()),
                        UserPlanCard.builder()
                                .cardId("card-002")
                                .title("提炼销售群摘要")
                                .description("基于竞品分析与销售应答话术文档，提炼一段销售群摘要")
                                .type(PlanCardTypeEnum.SUMMARY)
                                .status("PENDING")
                                .dependsOn(List.of("card-001"))
                                .build()
                ))
                .build();

        PlanBlueprint result = merger.merge(source, PlanPatchIntent.builder()
                .operation(PlanPatchOperation.UPDATE_STEP)
                .targetCardIds(List.of("card-001"))
                .newCardDrafts(List.of(PlanPatchCardDraft.builder()
                        .title("生成销售应答话术文档")
                        .description("基于客户反馈生成销售应答话术文档，不包含竞品分析")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .confidence(0.9d)
                .build(), "task-1");

        assertThat(result.getPlanCards().get(1).getDescription())
                .contains("基于销售应答话术文档")
                .doesNotContain("基于竞品分析与销售应答话术文档");
    }

    @Test
    void updateMultipleStepsAppliesDraftsByTargetOrder() {
        PlanBlueprint result = merger.merge(blueprint(), PlanPatchIntent.builder()
                .operation(PlanPatchOperation.UPDATE_STEP)
                .targetCardIds(List.of("card-001", "card-002"))
                .newCardDrafts(List.of(
                        PlanPatchCardDraft.builder()
                                .title("生成技术方案文档（含责任人风险清单）")
                                .description("文档风险清单增加责任人和缓解措施字段")
                                .type(PlanCardTypeEnum.DOC)
                                .build(),
                        PlanPatchCardDraft.builder()
                                .title("生成3页以内配套 PPT")
                                .description("基于技术方案文档生成3页以内的PPT")
                                .type(PlanCardTypeEnum.PPT)
                                .build()
                ))
                .confidence(0.95d)
                .build(), "task-1");

        assertThat(result.getPlanCards())
                .extracting(UserPlanCard::getType)
                .containsExactly(PlanCardTypeEnum.DOC, PlanCardTypeEnum.PPT, PlanCardTypeEnum.SUMMARY, PlanCardTypeEnum.PPT);
        assertThat(result.getPlanCards().get(0).getTitle()).contains("责任人风险清单");
        assertThat(result.getPlanCards().get(1).getTitle()).contains("3页以内配套 PPT");
    }

    private static PlanBlueprint blueprint() {
        return PlanBlueprint.builder()
                .planCards(List.of(
                        card("card-001", "生成技术方案文档", PlanCardTypeEnum.DOC, List.of()),
                        card("card-002", "生成配套 PPT", PlanCardTypeEnum.PPT, List.of("card-001")),
                        card("card-003", "生成群内项目进展摘要", PlanCardTypeEnum.SUMMARY, List.of("card-002")),
                        card("card-004", "生成老板汇报 PPT", PlanCardTypeEnum.PPT, List.of("card-003"))
                ))
                .build();
    }

    private static UserPlanCard card(String cardId, String title, PlanCardTypeEnum type, List<String> dependsOn) {
        return UserPlanCard.builder()
                .cardId(cardId)
                .title(title)
                .description(title)
                .type(type)
                .status("PENDING")
                .dependsOn(dependsOn)
                .build();
    }
}
