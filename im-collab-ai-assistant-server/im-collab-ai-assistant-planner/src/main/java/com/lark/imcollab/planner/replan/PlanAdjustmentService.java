package com.lark.imcollab.planner.replan;

import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import org.springframework.stereotype.Service;

@Service
public class PlanAdjustmentService {

    private final PlanPatchMerger planPatchMerger;

    public PlanAdjustmentService(PlanPatchMerger planPatchMerger) {
        this.planPatchMerger = planPatchMerger;
    }

    public TaskPlanGraph applyPatch(TaskPlanGraph graph, PlanPatch patch) {
        return planPatchMerger.merge(graph, patch);
    }
}
