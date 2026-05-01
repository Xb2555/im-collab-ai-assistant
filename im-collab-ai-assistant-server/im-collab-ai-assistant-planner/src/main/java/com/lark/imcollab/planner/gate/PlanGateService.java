package com.lark.imcollab.planner.gate;

import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PlanGateService {

    public PlanGateResult check(TaskPlanGraph graph, ExecutionContract contract) {
        List<String> reasons = new ArrayList<>();
        if (graph == null) {
            return new PlanGateResult(false, List.of("plan graph is required"));
        }
        if (contract == null) {
            reasons.add("execution contract is required");
        } else {
            if (isBlank(contract.getRawInstruction())) {
                reasons.add("execution contract rawInstruction is required");
            }
            if (isBlank(contract.getClarifiedInstruction())) {
                reasons.add("execution contract clarifiedInstruction is required");
            }
            if (contract.getAllowedArtifacts() == null || contract.getAllowedArtifacts().isEmpty()) {
                reasons.add("execution contract allowedArtifacts are required");
            }
        }
        if (graph.getDeliverables() == null || graph.getDeliverables().isEmpty()) {
            reasons.add("plan deliverables are required");
        }
        List<TaskStepRecord> steps = graph.getSteps() == null ? List.of() : graph.getSteps();
        if (steps.isEmpty()) {
            reasons.add("plan steps are required");
        }
        Map<String, TaskStepRecord> byId = new HashMap<>();
        Set<String> duplicated = new HashSet<>();
        for (TaskStepRecord step : steps) {
            if (step == null || isBlank(step.getStepId())) {
                reasons.add("stepId is required");
                continue;
            }
            if (byId.put(step.getStepId(), step) != null) {
                duplicated.add(step.getStepId());
            }
            if (isBlank(step.getAssignedWorker())) {
                reasons.add("step " + step.getStepId() + " missing assignedWorker");
            }
        }
        duplicated.forEach(stepId -> reasons.add("duplicate stepId: " + stepId));
        for (TaskStepRecord step : steps) {
            if (step == null || step.getDependsOn() == null) {
                continue;
            }
            for (String dependency : step.getDependsOn()) {
                if (!byId.containsKey(dependency)) {
                    reasons.add("step " + step.getStepId() + " depends on missing step " + dependency);
                }
            }
        }
        if (hasCycle(steps, byId)) {
            reasons.add("step dependencies contain a cycle");
        }
        return new PlanGateResult(reasons.isEmpty(), List.copyOf(reasons));
    }

    private boolean hasCycle(List<TaskStepRecord> steps, Map<String, TaskStepRecord> byId) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (TaskStepRecord step : steps) {
            if (step != null && hasCycle(step.getStepId(), byId, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycle(String stepId, Map<String, TaskStepRecord> byId, Set<String> visiting, Set<String> visited) {
        if (visited.contains(stepId)) {
            return false;
        }
        if (!visiting.add(stepId)) {
            return true;
        }
        TaskStepRecord step = byId.get(stepId);
        if (step != null && step.getDependsOn() != null) {
            for (String dependency : step.getDependsOn()) {
                if (byId.containsKey(dependency) && hasCycle(dependency, byId, visiting, visited)) {
                    return true;
                }
            }
        }
        visiting.remove(stepId);
        visited.add(stepId);
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
