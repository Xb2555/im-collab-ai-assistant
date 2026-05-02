package com.lark.imcollab.planner.replan;

import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import org.springframework.stereotype.Service;

@Service
public class ReplannerService {

    private final PlanAdjustmentService planAdjustmentService;

    public ReplannerService(PlanAdjustmentService planAdjustmentService) {
        this.planAdjustmentService = planAdjustmentService;
    }

    public TaskPlanGraph replanLocally(TaskPlanGraph graph, PlanPatch patch) {
        return planAdjustmentService.applyPatch(graph, patch);
    }
}
