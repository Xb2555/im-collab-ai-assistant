package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;

public interface ImTaskCommandFacade {

    PlanTaskSession confirmExecution(String taskId);

    PlanTaskSession retryExecution(String taskId, String feedback);

    void interruptExecution(String taskId);

    void cancelExecution(String taskId);

    TaskRuntimeSnapshot getRuntimeSnapshot(String taskId);
}
