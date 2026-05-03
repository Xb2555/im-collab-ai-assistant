package com.lark.imcollab.planner.gate;

import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class PlanGateService {

    private final PlannerCapabilityPolicy capabilityPolicy;

    public PlanGateService(PlannerCapabilityPolicy capabilityPolicy) {
        this.capabilityPolicy = capabilityPolicy;
    }

    public PlanGateService() {
        this(new PlannerCapabilityPolicy());
    }

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
        } else {
            for (String deliverable : graph.getDeliverables()) {
                if (!capabilityPolicy.supportsArtifact(deliverable)) {
                    reasons.add("unsupported deliverable: " + deliverable);
                }
                if (contract != null && contract.getAllowedArtifacts() != null && !contract.getAllowedArtifacts().isEmpty()
                        && capabilityPolicy.normalizeArtifact(deliverable)
                        .filter(normalized -> contract.getAllowedArtifacts().stream()
                                .map(capabilityPolicy::normalizeArtifact)
                                .flatMap(Optional::stream)
                                .noneMatch(normalized::equals))
                        .isPresent()) {
                    reasons.add("deliverable is outside execution contract: " + deliverable);
                }
            }
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
            } else if (step.getType() != null && capabilityPolicy.expectedWorker(step.getType())
                    .filter(expected -> !expected.equals(step.getAssignedWorker()))
                    .isPresent()) {
                reasons.add("step " + step.getStepId() + " worker does not match capability");
            }
            if (step.getType() == null || !capabilityPolicy.supportsStep(step.getType())) {
                reasons.add("step " + step.getStepId() + " has unsupported type");
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
        reasons.addAll(checkExecutionSupport(steps));
        return new PlanGateResult(reasons.isEmpty(), List.copyOf(reasons));
    }

    private List<String> checkExecutionSupport(List<TaskStepRecord> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        long docSteps = steps.stream()
                .filter(step -> step != null && step.getType() == StepTypeEnum.DOC_CREATE)
                .count();
        long pptSteps = steps.stream()
                .filter(step -> step != null && step.getType() == StepTypeEnum.PPT_CREATE)
                .count();
        List<String> reasons = new ArrayList<>();
        if (docSteps > 1) {
            reasons.add("multiple DOC steps are not executable in one run; merge extra sections into the main DOC");
        }
        if (pptSteps > 1) {
            reasons.add("multiple PPT steps are not executable in one run; keep a single PPT deliverable");
        }
        return reasons;
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
