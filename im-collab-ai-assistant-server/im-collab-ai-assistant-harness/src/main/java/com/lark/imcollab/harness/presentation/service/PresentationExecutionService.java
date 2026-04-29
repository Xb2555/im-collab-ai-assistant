package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.domain.Approval;

public interface PresentationExecutionService {
    void execute(String taskId);
    void resume(String taskId, Approval approval);
}
