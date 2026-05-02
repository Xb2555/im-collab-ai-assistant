package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.planner.replan.CardPlanPatchMerger;
import com.lark.imcollab.planner.replan.PlanPatchIntent;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class PlannerPatchTool {

    private final CardPlanPatchMerger patchMerger;

    public PlannerPatchTool(CardPlanPatchMerger patchMerger) {
        this.patchMerger = patchMerger;
    }

    @Tool(description = "Scenario B: merge a local plan patch while preserving untouched plan cards.")
    public PlanBlueprint merge(PlanBlueprint currentPlan, PlanPatchIntent patchIntent, String taskId) {
        return patchMerger.merge(currentPlan, patchIntent, taskId);
    }
}
