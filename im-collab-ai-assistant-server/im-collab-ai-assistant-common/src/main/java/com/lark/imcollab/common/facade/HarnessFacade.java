package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.domain.Approval;
import com.lark.imcollab.common.domain.Task;

public interface HarnessFacade {
    Task startExecution(String taskId);
    Task resumeExecution(String taskId, Approval approval);
    Task abortExecution(String taskId);
}
