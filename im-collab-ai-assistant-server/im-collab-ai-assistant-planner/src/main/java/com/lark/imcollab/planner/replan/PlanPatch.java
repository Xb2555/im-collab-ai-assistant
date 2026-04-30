package com.lark.imcollab.planner.replan;

import com.lark.imcollab.common.model.entity.TaskStepRecord;

import java.util.List;

public record PlanPatch(
        List<TaskStepRecord> upsertSteps,
        List<String> supersededStepIds,
        String reason
) {
}
