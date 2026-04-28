package com.lark.imcollab.harness.facade;

import com.lark.imcollab.common.facade.ExecutionHarnessFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;

@Deprecated
public class DefaultExecutionHarnessFacade implements ExecutionHarnessFacade {

    @Override
    public PlanTaskSession startExecution(String taskId) { throw new UnsupportedOperationException(); }

    @Override
    public PlanTaskSession interruptExecution(String taskId) { throw new UnsupportedOperationException(); }

    @Override
    public PlanTaskSession resumeExecution(String taskId, String userFeedback) { throw new UnsupportedOperationException(); }
}
