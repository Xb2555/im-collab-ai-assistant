package com.lark.imcollab.planner.plan;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.lark.imcollab.common.domain.*;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class Replanner {

    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    @Qualifier("planningAgent")
    private final ReactAgent planningAgent;

    public Task replan(String taskId, String userFeedback) throws GraphRunnerException {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        task.setStatus(TaskStatus.PLANNING);
        task.setUpdatedAt(Instant.now());
        taskRepository.save(task);

        String prompt = "原始需求：" + task.getRawInstruction() + "\n用户反馈：" + userFeedback;
        planningAgent.call(prompt);

        task.setStatus(TaskStatus.PLAN_READY);
        task.setUpdatedAt(Instant.now());
        taskRepository.save(task);

        eventRepository.save(TaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .type(TaskEventType.PLAN_READY)
                .payload(userFeedback)
                .occurredAt(Instant.now())
                .build());

        return task;
    }
}
