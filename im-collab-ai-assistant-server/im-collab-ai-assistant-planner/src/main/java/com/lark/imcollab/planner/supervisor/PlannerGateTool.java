package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.planner.gate.PlanGateResult;
import com.lark.imcollab.planner.gate.PlanGateService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class PlannerGateTool {

    private final PlanGateService planGateService;

    public PlannerGateTool(PlanGateService planGateService) {
        this.planGateService = planGateService;
    }

    @Tool(description = "Scenario B: validate whether a planner graph and execution contract are executable.")
    public PlanGateResult check(TaskPlanGraph graph, ExecutionContract contract) {
        return planGateService.check(graph, contract);
    }
}
