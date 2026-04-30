package com.lark.imcollab.planner.planning;

import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FastPlanBlueprintFactoryTest {

    private final FastPlanBlueprintFactory factory = new FastPlanBlueprintFactory();

    @Test
    void buildsFastDocPptSummaryPlanForCommonRequest() {
        String instruction = "根据飞书项目协作方案生成技术方案文档（含 Mermaid 架构图），准备配套 PPT，并最后输出一段可以直接发到群里的项目进展摘要";

        IntentSnapshot intent = factory.buildIntentSnapshot(instruction, null).orElseThrow();
        PlanBlueprint blueprint = factory.buildBlueprint("task-1", instruction, intent).orElseThrow();

        assertThat(intent.getDeliverableTargets()).containsExactly("DOC", "PPT", "SUMMARY");
        assertThat(blueprint.getPlanCards())
                .extracting(UserPlanCard::getType)
                .containsExactly(PlanCardTypeEnum.DOC, PlanCardTypeEnum.PPT, PlanCardTypeEnum.SUMMARY);
        assertThat(blueprint.getPlanCards().get(1).getDependsOn()).containsExactly("card-001");
        assertThat(blueprint.getPlanCards().get(2).getDependsOn()).containsExactly("card-002");
        assertThat(blueprint.getSuccessCriteria()).anyMatch(value -> value.contains("Mermaid"));
    }

    @Test
    void vagueInstructionDoesNotUseFastPath() {
        assertThat(factory.buildIntentSnapshot("帮我做一下", null)).isEmpty();
        assertThat(factory.buildBlueprint("task-1", "帮我做一下", null)).isEmpty();
    }
}
