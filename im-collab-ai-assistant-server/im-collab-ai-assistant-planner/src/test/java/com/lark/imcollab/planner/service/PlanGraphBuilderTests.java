package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanGraphBuilderTests {

    private final PlanGraphBuilder builder = new PlanGraphBuilder();

    @Test
    void shouldConvertPlanCardsToRuntimeSteps() {
        TaskPlanGraph graph = builder.build("task-1", PlanBlueprint.builder()
                .taskBrief("Prepare weekly boss update")
                .deliverables(List.of("DOC", "PPT"))
                .successCriteria(List.of("Clear milestones"))
                .risks(List.of("Missing progress data"))
                .planCards(List.of(
                        UserPlanCard.builder()
                                .cardId("doc-step")
                                .title("Draft report")
                                .description("Create boss-facing report")
                                .type(PlanCardTypeEnum.DOC)
                                .status("PENDING")
                                .build(),
                        UserPlanCard.builder()
                                .cardId("ppt-step")
                                .title("Create slides")
                                .type(PlanCardTypeEnum.PPT)
                                .status("COMPLETED")
                                .progress(100)
                                .dependsOn(List.of("doc-step"))
                                .build()
                ))
                .build());

        assertThat(graph.getTaskId()).isEqualTo("task-1");
        assertThat(graph.getSteps()).hasSize(2);
        assertThat(graph.getSteps().get(0).getType()).isEqualTo(StepTypeEnum.DOC_CREATE);
        assertThat(graph.getSteps().get(0).getStatus()).isEqualTo(StepStatusEnum.READY);
        assertThat(graph.getSteps().get(1).getType()).isEqualTo(StepTypeEnum.PPT_CREATE);
        assertThat(graph.getSteps().get(1).getStatus()).isEqualTo(StepStatusEnum.COMPLETED);
        assertThat(graph.getSteps().get(1).getDependsOn()).containsExactly("doc-step");
    }
}
