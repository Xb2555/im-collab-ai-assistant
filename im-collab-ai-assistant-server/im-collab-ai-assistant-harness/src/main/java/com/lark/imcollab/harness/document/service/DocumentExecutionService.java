package com.lark.imcollab.harness.document.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;

public interface DocumentExecutionService {

    PlanTaskSession execute(String taskId, String cardId, String userFeedback);

    PlanTaskSession resume(String taskId, String userFeedback);

    PlanTaskSession interrupt(String taskId);
}
