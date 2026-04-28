package com.lark.imcollab.planner.plan;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.lark.imcollab.common.domain.*;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.planner.intent.IntentRouter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

@Component
public class TaskPlanner {

    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final ReactAgent planningAgent;
    private final IntentRouter intentRouter;

    public TaskPlanner(
            TaskRepository taskRepository,
            TaskEventRepository eventRepository,
            @Qualifier("planningAgent") ReactAgent planningAgent,
            IntentRouter intentRouter) {
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.planningAgent = planningAgent;
        this.intentRouter = intentRouter;
    }

    public Task plan(Conversation conversation) throws GraphRunnerException {
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

        planningAgent.call(conversation.getRawMessage());
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
