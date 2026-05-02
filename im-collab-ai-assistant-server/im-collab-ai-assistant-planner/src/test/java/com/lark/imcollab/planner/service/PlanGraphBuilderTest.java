package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanGraphBuilderTest {

    private final PlanGraphBuilder builder = new PlanGraphBuilder();

    @Test
    void derivesExecutableDeliverablesFromCardTypesInsteadOfFreeTextNames() {
        PlanBlueprint blueprint = PlanBlueprint.builder()
                .deliverables(List.of("上线变更方案文档", "风险摘要"))
                .planCards(List.of(
                        UserPlanCard.builder()
                                .cardId("card-001")
                                .title("生成上线变更方案文档")
                                .type(PlanCardTypeEnum.DOC)
                                .status("PENDING")
                                .build(),
                        UserPlanCard.builder()
                                .cardId("card-002")
                                .title("提炼上线变更风险摘要")
                                .type(PlanCardTypeEnum.SUMMARY)
                                .status("PENDING")
                                .dependsOn(List.of("card-001"))
                                .build()
                ))
                .build();

        TaskPlanGraph graph = builder.build("task-1", blueprint);

        assertThat(graph.getDeliverables()).containsExactly("DOC", "SUMMARY");
        assertThat(graph.getSteps()).hasSize(2);
    }
}
