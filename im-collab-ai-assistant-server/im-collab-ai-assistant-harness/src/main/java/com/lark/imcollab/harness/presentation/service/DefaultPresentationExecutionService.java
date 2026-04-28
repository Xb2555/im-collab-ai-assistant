package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultPresentationExecutionService implements PresentationExecutionService {

    private final PresentationWorkflowSkeletonService skeletonService;

    @Override
    public PlanTaskSession reserveExecution(String taskId, String cardId) {
        return skeletonService.reserveSkeleton(taskId, cardId);
    }
}
