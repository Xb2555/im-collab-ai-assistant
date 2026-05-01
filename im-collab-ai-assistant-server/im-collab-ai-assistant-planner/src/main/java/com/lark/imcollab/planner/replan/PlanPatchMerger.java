package com.lark.imcollab.planner.replan;

import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlanPatchMerger {

    public TaskPlanGraph merge(TaskPlanGraph graph, PlanPatch patch) {
        if (graph == null || patch == null) {
            return graph;
        }
        Map<String, TaskStepRecord> merged = new LinkedHashMap<>();
        for (TaskStepRecord step : graph.getSteps() == null ? List.<TaskStepRecord>of() : graph.getSteps()) {
            if (step != null && step.getStepId() != null) {
                merged.put(step.getStepId(), step);
            }
        }
        for (String stepId : patch.supersededStepIds() == null ? List.<String>of() : patch.supersededStepIds()) {
            TaskStepRecord step = merged.get(stepId);
            if (step != null && step.getStatus() != StepStatusEnum.COMPLETED) {
                step.setStatus(StepStatusEnum.SUPERSEDED);
                step.setVersion(step.getVersion() + 1);
            }
        }
        for (TaskStepRecord step : patch.upsertSteps() == null ? List.<TaskStepRecord>of() : patch.upsertSteps()) {
            if (step != null && step.getStepId() != null) {
                merged.put(step.getStepId(), step);
            }
        }
        graph.setSteps(new ArrayList<>(merged.values()));
        return graph;
    }
}
