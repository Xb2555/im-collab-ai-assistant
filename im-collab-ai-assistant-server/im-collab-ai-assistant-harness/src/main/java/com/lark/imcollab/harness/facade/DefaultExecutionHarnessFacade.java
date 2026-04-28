package com.lark.imcollab.harness.facade;

import com.lark.imcollab.common.facade.ExecutionHarnessFacade;
import com.lark.imcollab.common.facade.PlannerRuntimeFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.harness.service.ExecutionTaskDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultExecutionHarnessFacade implements ExecutionHarnessFacade {

    private final PlannerRuntimeFacade plannerRuntimeFacade;
    private final ExecutionTaskDispatcher taskDispatcher;

    @Override
    public PlanTaskSession startExecution(String taskId) {
        return taskDispatcher.dispatch(plannerRuntimeFacade.getSession(taskId));
    }

    @Override
    public PlanTaskSession interruptExecution(String taskId) {
        return taskDispatcher.interrupt(taskId);
    }

    @Override
    public PlanTaskSession resumeExecution(String taskId, String userFeedback) {
        return taskDispatcher.resume(taskId, userFeedback);
    }
}
