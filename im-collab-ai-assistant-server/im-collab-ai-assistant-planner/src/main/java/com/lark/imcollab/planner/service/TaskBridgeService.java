package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.domain.*;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
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

    public Task ensureTask(PlanTaskSession session) {
        return taskRepository.findById(session.getTaskId())
                .orElseGet(() -> createTask(session));
    }

    private Task createTask(PlanTaskSession session) {
        TaskType type = resolveType(session.getPlanBlueprint());
        Task task = Task.builder()
                .taskId(session.getTaskId())
                .rawInstruction(session.getPlanBlueprintSummary())
                .type(type)
                .status(TaskStatus.PLAN_READY)
                .steps(new ArrayList<>())
                .artifacts(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        taskRepository.save(task);
        eventRepository.save(TaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(task.getTaskId())
                .type(TaskEventType.PLAN_READY)
                .occurredAt(Instant.now())
                .build());
        return task;
    }

    private TaskType resolveType(PlanBlueprint blueprint) {
        if (blueprint == null || blueprint.getPlanCards() == null) {
            return TaskType.WRITE_DOC;
        }
        List<UserPlanCard> cards = blueprint.getPlanCards();
        boolean hasDoc = cards.stream().anyMatch(c -> c.getType() == PlanCardTypeEnum.DOC);
        boolean hasPpt = cards.stream().anyMatch(c -> c.getType() == PlanCardTypeEnum.PPT);
        if (hasDoc && hasPpt) return TaskType.MIXED;
        if (hasPpt) return TaskType.WRITE_SLIDES;
        return TaskType.WRITE_DOC;
    }
}

