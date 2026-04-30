package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;

public interface ImTaskCommandFacade {

    PlanTaskSession confirmExecution(String taskId);

    TaskRuntimeSnapshot getRuntimeSnapshot(String taskId);
}
