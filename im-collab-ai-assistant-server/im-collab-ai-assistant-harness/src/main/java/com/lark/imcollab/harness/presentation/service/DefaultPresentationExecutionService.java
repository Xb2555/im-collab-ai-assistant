package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.domain.Approval;
import com.lark.imcollab.common.domain.TaskEvent;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.port.TaskEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultPresentationExecutionService implements PresentationExecutionService {

    private final PresentationWorkflowSkeletonService skeletonService;
    private final TaskEventRepository eventRepository;

    @Override
    public void execute(String taskId) {
        skeletonService.reserveSkeleton(taskId);
        publishEvent(taskId, null, TaskEventType.STEP_COMPLETED);
    }

    @Override
    public void resume(String taskId, Approval approval) {
        skeletonService.resumeSkeleton(taskId, approval.getUserFeedback());
        publishEvent(taskId, approval.getStepId(), TaskEventType.STEP_COMPLETED);
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
