package com.lark.imcollab.harness.document.service;

import com.lark.imcollab.common.domain.Approval;

public interface DocumentExecutionService {
    void execute(String taskId);
    void resume(String taskId, Approval approval);
}
