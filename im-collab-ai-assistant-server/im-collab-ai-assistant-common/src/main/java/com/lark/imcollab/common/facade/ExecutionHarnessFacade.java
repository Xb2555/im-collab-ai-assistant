package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.PlanTaskSession;

/**
 * @deprecated replaced by {@link HarnessFacade}
 */
@Deprecated
public interface ExecutionHarnessFacade {

    PlanTaskSession startExecution(String taskId);

    PlanTaskSession interruptExecution(String taskId);

    PlanTaskSession resumeExecution(String taskId, String userFeedback);
}
