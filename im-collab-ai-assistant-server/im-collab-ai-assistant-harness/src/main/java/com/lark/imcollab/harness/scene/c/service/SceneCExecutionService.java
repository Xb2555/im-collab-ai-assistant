package com.lark.imcollab.harness.scene.c.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;

public interface SceneCExecutionService {

    PlanTaskSession execute(String taskId, String cardId, String userFeedback);

    PlanTaskSession resume(String taskId, String userFeedback);

    PlanTaskSession interrupt(String taskId);
}
