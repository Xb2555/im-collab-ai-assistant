package com.lark.imcollab.planner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanQualityServiceTest {

    private final PlanQualityService service = new PlanQualityService(
            new ObjectMapper(),
            List.of(),
            new ExecutionContractFactory()
    );

    @Test
    void intentReadyNormalizesStringScenarioPathFromAgentOutput() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .build();

        IntentSnapshot snapshot = IntentSnapshot.builder()
                .userGoal("生成技术方案文档和 PPT")
                .scenarioPath(rawScenarioPath("C_DOC", "D_PRESENTATION"))
                .build();

        service.applyIntentReady(session, snapshot);

        assertThat(session.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.INTENT_READY);
        assertThat(session.getIntentSnapshot().getScenarioPath())
                .containsExactly(ScenarioCodeEnum.C_DOC, ScenarioCodeEnum.D_PRESENTATION);
        assertThat(session.getScenarioPath())
                .containsExactly(
                        ScenarioCodeEnum.A_IM,
                        ScenarioCodeEnum.B_PLANNING,
                        ScenarioCodeEnum.C_DOC,
                        ScenarioCodeEnum.D_PRESENTATION
                );
    }

    @Test
    void adjustmentTrustsAgentProvidedCardsAndDoesNotInferByKeywords() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("根据飞书项目协作方案生成技术方案文档，包含 Mermaid 架构图，并准备配套 PPT 初稿")
                .planBlueprint(PlanBlueprint.builder()
                        .taskBrief("飞书项目协作方案技术方案")
                        .planCards(List.of(
                                card("card-001", "生成技术方案文档", PlanCardTypeEnum.DOC),
                                card("card-002", "生成配套评审PPT初稿", PlanCardTypeEnum.PPT),
                                card("card-003", "生成面向老板汇报的PPT大纲页", PlanCardTypeEnum.PPT)
                        ))
                        .build())
                .build();

        PlanBlueprint updated = PlanBlueprint.builder()
                .planCards(List.of(
                        card("card-001", "生成技术方案文档", PlanCardTypeEnum.DOC),
                        card("card-002", "生成配套评审PPT初稿", PlanCardTypeEnum.PPT),
                        card("card-003", "生成面向老板汇报的PPT大纲页", PlanCardTypeEnum.PPT),
                        card("card-004", "生成群内项目进展摘要", PlanCardTypeEnum.SUMMARY)
                ))
                .build();

        service.applyPlanAdjustment(
                session,
                updated,
                "再加一条：最后输出一段可以直接发到群里的项目进展摘要"
        );

        assertThat(session.getPlanCards())
                .extracting(UserPlanCard::getTitle)
                .containsExactly(
                        "生成技术方案文档",
                        "生成配套评审PPT初稿",
                        "生成面向老板汇报的PPT大纲页",
                        "生成群内项目进展摘要"
                );
        assertThat(session.getPlanCards().get(3).getCardId()).isEqualTo("card-004");
        assertThat(session.getPlanCards().get(3).getType()).isEqualTo(PlanCardTypeEnum.SUMMARY);
    }

    @Test
    void adjustmentNormalizesDuplicateCardIdsBeforePlanGate() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("根据飞书项目协作方案生成技术方案文档，包含 Mermaid 架构图，并准备配套 PPT 初稿")
                .planBlueprint(PlanBlueprint.builder()
                        .taskBrief("飞书项目协作方案技术方案")
                        .planCards(List.of(
                                card("card-001", "生成技术方案文档", PlanCardTypeEnum.DOC),
                                card("card-002", "生成配套评审PPT初稿", PlanCardTypeEnum.PPT),
                                card("card-003", "生成群内项目进展摘要", PlanCardTypeEnum.SUMMARY)
                        ))
                        .build())
                .build();

        PlanBlueprint updated = PlanBlueprint.builder()
                .planCards(List.of(
                        card("card-001", "生成技术方案文档", PlanCardTypeEnum.DOC),
                        card("card-001", "生成老板汇报 PPT", PlanCardTypeEnum.PPT)
                ))
                .build();

        service.applyPlanAdjustment(session, updated, "再加上回复老板生成的ppt");

        assertThat(session.getPlanCards())
                .extracting(UserPlanCard::getTitle)
                .contains("生成老板汇报 PPT");
        assertThat(session.getPlanCards())
                .extracting(UserPlanCard::getCardId)
                .doesNotHaveDuplicates();
        assertThat(session.getPlanCards())
                .filteredOn(card -> "生成老板汇报 PPT".equals(card.getTitle()))
                .singleElement()
                .extracting(UserPlanCard::getCardId)
                .isEqualTo("card-002");
    }

    private static UserPlanCard card(String cardId, String title, PlanCardTypeEnum type) {
        return UserPlanCard.builder()
                .cardId(cardId)
                .title(title)
                .type(type)
                .dependsOn(List.of())
                .build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<ScenarioCodeEnum> rawScenarioPath(String... values) {
        return (List) List.of(values);
    }
}
