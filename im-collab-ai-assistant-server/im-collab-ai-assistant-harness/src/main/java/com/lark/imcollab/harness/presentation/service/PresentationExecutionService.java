package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;

public interface PresentationExecutionService {

    PlanTaskSession reserveExecution(String taskId, String cardId);
}
