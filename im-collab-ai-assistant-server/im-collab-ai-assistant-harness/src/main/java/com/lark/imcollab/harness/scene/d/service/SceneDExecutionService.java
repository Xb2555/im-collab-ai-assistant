package com.lark.imcollab.harness.scene.d.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;

public interface SceneDExecutionService {

    PlanTaskSession reserveExecution(String taskId, String cardId);
}
