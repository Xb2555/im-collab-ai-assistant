package com.lark.imcollab.planner.plan;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.lark.imcollab.common.domain.*;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.planner.intent.IntentRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TaskPlanner {

    private final IntentRouter intentRouter;
    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    @Qualifier("planningAgent")
    private final ReactAgent planningAgent;

    public Task plan(Conversation conversation) {
        TaskType type = intentRouter.route(conversation);

        Task task = Task.builder()
                .taskId(UUID.randomUUID().toString())
                .conversationId(conversation.getConversationId())
                .userId(conversation.getUserId())
                .rawInstruction(conversation.getRawMessage())
                .type(type)
                .status(TaskStatus.PLANNING)
                .steps(new ArrayList<>())
                .artifacts(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        taskRepository.save(task);
        publishEvent(task.getTaskId(), TaskEventType.TASK_CREATED);

        String planResult = planningAgent.call(conversation.getRawMessage());
        task.setStatus(TaskStatus.PLAN_READY);
        task.setUpdatedAt(Instant.now());
        taskRepository.save(task);
        publishEvent(task.getTaskId(), TaskEventType.PLAN_READY);

        return task;
    }

    private void publishEvent(String taskId, TaskEventType type) {
        eventRepository.save(TaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .type(type)
                .occurredAt(Instant.now())
                .build());
    }
}
