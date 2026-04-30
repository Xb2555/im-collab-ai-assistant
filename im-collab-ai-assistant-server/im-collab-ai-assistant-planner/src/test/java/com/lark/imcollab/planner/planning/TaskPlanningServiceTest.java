package com.lark.imcollab.planner.planning;

import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import com.lark.imcollab.planner.service.ExecutionContractFactory;
import com.lark.imcollab.planner.service.PlanGraphBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPlanningServiceTest {

    @Test
    void buildsPlanGraphAndExecutionContractFromSession() {
        TaskPlanningService service = new TaskPlanningService(new PlanGraphBuilder(), new ExecutionContractFactory());
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .rawInstruction("写一份包含 Mermaid 架构图的技术文档")
                .planBlueprint(PlanBlueprint.builder()
                        .taskBrief("技术文档")
                        .deliverables(List.of("DOC"))
                        .planCards(List.of(UserPlanCard.builder()
                                .cardId("doc-step")
                                .title("创建文档")
                                .type(PlanCardTypeEnum.DOC)
                                .build()))
                        .build())
                .build();

        TaskPlanningResult result = service.buildReadyPlan(session);

        assertThat(result.graph().getSteps()).singleElement()
                .extracting(step -> step.getType())
                .isEqualTo(StepTypeEnum.DOC_CREATE);
        assertThat(result.executionContract())
                .extracting(ExecutionContract::getPrimaryArtifact)
                .isEqualTo("DOC");
        assertThat(result.executionContract().getDiagramRequirement().isRequired()).isTrue();
    }
}
