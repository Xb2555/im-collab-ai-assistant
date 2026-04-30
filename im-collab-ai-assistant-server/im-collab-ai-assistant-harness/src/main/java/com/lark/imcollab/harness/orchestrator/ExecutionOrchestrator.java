package com.lark.imcollab.harness.orchestrator;

import com.lark.imcollab.common.domain.*;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.StepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ExecutionOrchestrator {

    private final TaskRepository taskRepository;
    private final StepRepository stepRepository;
    private final TaskEventRepository eventRepository;
    private final StepDispatcher stepDispatcher;
    private final ExecutionIntakeGate executionIntakeGate;

    public Task start(String taskId) {
        Task task = taskRepository.findById(taskId).orElseGet(() -> {
            Task fallback = Task.builder()
                    .taskId(taskId)
                    .type(TaskType.WRITE_DOC)
                    .status(TaskStatus.PLAN_READY)
                    .steps(new java.util.ArrayList<>())
                    .artifacts(new java.util.ArrayList<>())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            taskRepository.save(fallback);
            return fallback;
        });

        task = executionIntakeGate.freeze(task);
        task.setStatus(TaskStatus.EXECUTING);
        task.setUpdatedAt(Instant.now());
        taskRepository.save(task);
        publishEvent(taskId, null, TaskEventType.STEP_STARTED);

        stepDispatcher.dispatch(task);
        return taskRepository.findById(taskId).orElse(task);
    }

    public Task resume(String taskId, Approval approval) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        publishEvent(taskId, approval.getStepId(), TaskEventType.APPROVAL_DECIDED);

        switch (approval.getStatus()) {
            case APPROVED -> stepDispatcher.resumeAfterApproval(task, approval);
            case MODIFIED -> {
                task.setRawInstruction(approval.getUserFeedback());
                task.setUpdatedAt(Instant.now());
                taskRepository.save(task);
                stepDispatcher.resumeAfterApproval(task, approval);
            }
            case REJECTED -> {
                task.setStatus(TaskStatus.ABORTED);
                task.setFailReason("User rejected: " + approval.getUserFeedback());
                task.setUpdatedAt(Instant.now());
                taskRepository.save(task);
                publishEvent(taskId, approval.getStepId(), TaskEventType.TASK_ABORTED);
            }
            default -> stepDispatcher.resumeAfterApproval(task, approval);
        }
        return taskRepository.findById(taskId).orElse(task);
    }

    public Task abort(String taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        task.setStatus(TaskStatus.ABORTED);
        task.setUpdatedAt(Instant.now());
        taskRepository.save(task);
        publishEvent(taskId, null, TaskEventType.TASK_ABORTED);
        return task;
    }

    private void publishEvent(String taskId, String stepId, TaskEventType type) {
        eventRepository.save(TaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .stepId(stepId)
                .type(type)
                .occurredAt(Instant.now())
                .build());
    }
}
