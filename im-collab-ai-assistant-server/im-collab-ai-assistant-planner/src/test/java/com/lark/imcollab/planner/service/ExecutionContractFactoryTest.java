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
}
