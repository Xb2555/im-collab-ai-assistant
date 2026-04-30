package com.lark.imcollab.harness.orchestrator;

import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskType;
import com.lark.imcollab.common.model.entity.DiagramRequirement;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class ExecutionIntakeGate {

    public Task freeze(Task task) {
        if (task == null) {
            throw new IllegalArgumentException("Task is required");
        }
        ExecutionContract contract = task.getExecutionContract();
        if (contract == null) {
            contract = buildFallbackContract(task);
        }
        if (isBlank(contract.getRawInstruction())) {
            contract.setRawInstruction(task.getRawInstruction());
        }
        if (isBlank(contract.getClarifiedInstruction())) {
            contract.setClarifiedInstruction(firstNonBlank(task.getClarifiedInstruction(), contract.getRawInstruction()));
        }
        if (isBlank(contract.getTaskBrief())) {
            contract.setTaskBrief(firstNonBlank(task.getTaskBrief(), contract.getClarifiedInstruction(), contract.getRawInstruction()));
        }
        if (contract.getAllowedArtifacts() == null || contract.getAllowedArtifacts().isEmpty()) {
            contract.setAllowedArtifacts(resolveAllowedArtifacts(task.getType()));
        }
        if (contract.getRequestedArtifacts() == null || contract.getRequestedArtifacts().isEmpty()) {
            contract.setRequestedArtifacts(contract.getAllowedArtifacts());
        }
        if (isBlank(contract.getPrimaryArtifact()) && contract.getAllowedArtifacts() != null && !contract.getAllowedArtifacts().isEmpty()) {
            contract.setPrimaryArtifact(contract.getAllowedArtifacts().get(0));
        }
        if (isBlank(contract.getCrossArtifactPolicy())) {
            contract.setCrossArtifactPolicy("FORBID_UNLESS_EXPLICIT");
        }
        if (isBlank(contract.getTemplateStrategy())) {
            contract.setTemplateStrategy("REPORT");
        }
        if (contract.getDiagramRequirement() == null) {
            contract.setDiagramRequirement(DiagramRequirement.builder()
                    .required(false)
                    .types(List.of())
                    .format("MERMAID")
                    .placement("INLINE_DOC")
                    .count(0)
                    .build());
        }
        contract.setFrozenAt(firstNonBlankInstant(contract.getFrozenAt(), Instant.now()));

        task.setRawInstruction(firstNonBlank(task.getRawInstruction(), contract.getRawInstruction()));
        task.setClarifiedInstruction(firstNonBlank(task.getClarifiedInstruction(), contract.getClarifiedInstruction()));
        task.setTaskBrief(firstNonBlank(task.getTaskBrief(), contract.getTaskBrief()));
        task.setExecutionContract(contract);
        task.setType(resolveTaskType(contract));

        validate(task);
        return task;
    }

    private void validate(Task task) {
        ExecutionContract contract = task.getExecutionContract();
        if (contract == null) {
            throw new IllegalStateException("Execution contract is required");
        }
        if (isBlank(contract.getRawInstruction())) {
            throw new IllegalStateException("Execution contract missing rawInstruction");
        }
        if (isBlank(contract.getClarifiedInstruction())) {
            throw new IllegalStateException("Execution contract missing clarifiedInstruction");
        }
        if (contract.getAllowedArtifacts() == null || contract.getAllowedArtifacts().isEmpty()) {
            throw new IllegalStateException("Execution contract missing allowedArtifacts");
        }
    }

    private ExecutionContract buildFallbackContract(Task task) {
        List<String> allowedArtifacts = resolveAllowedArtifacts(task.getType());
        return ExecutionContract.builder()
                .taskId(task.getTaskId())
                .rawInstruction(task.getRawInstruction())
                .clarifiedInstruction(firstNonBlank(task.getClarifiedInstruction(), task.getRawInstruction()))
                .taskBrief(firstNonBlank(task.getTaskBrief(), task.getClarifiedInstruction(), task.getRawInstruction()))
                .requestedArtifacts(allowedArtifacts)
                .allowedArtifacts(allowedArtifacts)
                .primaryArtifact(allowedArtifacts.get(0))
                .crossArtifactPolicy("FORBID_UNLESS_EXPLICIT")
                .templateStrategy("REPORT")
                .diagramRequirement(DiagramRequirement.builder()
                        .required(false)
                        .types(List.of())
                        .format("MERMAID")
                        .placement("INLINE_DOC")
                        .count(0)
                        .build())
                .frozenAt(Instant.now())
                .build();
    }

    private TaskType resolveTaskType(ExecutionContract contract) {
        List<String> allowedArtifacts = contract.getAllowedArtifacts();
        boolean hasDoc = allowedArtifacts.stream().anyMatch(value -> "DOC".equalsIgnoreCase(value));
        boolean hasPpt = allowedArtifacts.stream().anyMatch(value -> "PPT".equalsIgnoreCase(value));
        if (hasDoc && hasPpt) {
            return TaskType.MIXED;
        }
        if (hasPpt) {
            return TaskType.WRITE_SLIDES;
        }
        return TaskType.WRITE_DOC;
    }

    private List<String> resolveAllowedArtifacts(TaskType taskType) {
        if (taskType == TaskType.WRITE_SLIDES) {
            return List.of("PPT");
        }
        if (taskType == TaskType.MIXED) {
            return List.of("DOC", "PPT");
        }
        return List.of("DOC");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private Instant firstNonBlankInstant(Instant value, Instant fallback) {
        return value != null ? value : fallback;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
