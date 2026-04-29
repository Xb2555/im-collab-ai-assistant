package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.domain.*;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskBridgeService {

    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final ExecutionContractFactory executionContractFactory;

    public Task ensureTask(PlanTaskSession session) {
        Task existing = taskRepository.findById(session.getTaskId()).orElse(null);
        Task task = createTask(session, existing);
        taskRepository.save(task);
        if (existing == null) {
            eventRepository.save(TaskEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .taskId(task.getTaskId())
                    .type(TaskEventType.PLAN_READY)
                    .occurredAt(Instant.now())
                    .build());
        }
        return task;
    }

    private Task createTask(PlanTaskSession session, Task existing) {
        ExecutionContract contract = executionContractFactory.build(session);
        return Task.builder()
                .taskId(session.getTaskId())
                .rawInstruction(contract.getRawInstruction())
                .clarifiedInstruction(contract.getClarifiedInstruction())
                .taskBrief(contract.getTaskBrief())
                .executionContract(contract)
                .type(resolveType(contract))
                .status(TaskStatus.PLAN_READY)
                .steps(new ArrayList<>())
                .artifacts(new ArrayList<>())
                .createdAt(existing == null ? Instant.now() : existing.getCreatedAt())
                .updatedAt(Instant.now())
                .build();
    }

    private TaskType resolveType(ExecutionContract contract) {
        if (contract == null || contract.getAllowedArtifacts() == null || contract.getAllowedArtifacts().isEmpty()) {
            return TaskType.WRITE_DOC;
        }
        List<String> artifacts = contract.getAllowedArtifacts();
        boolean hasDoc = artifacts.stream().anyMatch(value -> "DOC".equalsIgnoreCase(value));
        boolean hasPpt = artifacts.stream().anyMatch(value -> "PPT".equalsIgnoreCase(value));
        if (hasDoc && hasPpt) return TaskType.MIXED;
        if (hasPpt) return TaskType.WRITE_SLIDES;
        return TaskType.WRITE_DOC;
    }
}
