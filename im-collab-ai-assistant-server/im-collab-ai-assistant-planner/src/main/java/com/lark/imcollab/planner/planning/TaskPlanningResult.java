package com.lark.imcollab.planner.planning;

import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.TaskPlanGraph;

public record TaskPlanningResult(
        PlanBlueprint blueprint,
        TaskPlanGraph graph,
        ExecutionContract executionContract
) {
}
