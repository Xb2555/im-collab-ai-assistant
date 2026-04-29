package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.port.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PresentationWorkflowSkeletonService {

    private final TaskRepository taskRepository;

    public void reserveSkeleton(String taskId) {
        taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    public void resumeSkeleton(String taskId, String userFeedback) {
        taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }
}
