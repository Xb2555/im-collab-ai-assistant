package com.lark.imcollab.harness.facade;

import com.lark.imcollab.common.facade.ExecutionHarnessFacade;
import com.lark.imcollab.common.facade.PlannerRuntimeFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.harness.service.SceneTaskDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultExecutionHarnessFacade implements ExecutionHarnessFacade {

    private final PlannerRuntimeFacade plannerRuntimeFacade;
    private final SceneTaskDispatcher sceneTaskDispatcher;

    @Override
    public PlanTaskSession startExecution(String taskId) {
        return sceneTaskDispatcher.dispatch(plannerRuntimeFacade.getSession(taskId));
    }

    @Override
    public PlanTaskSession interruptExecution(String taskId) {
        return sceneTaskDispatcher.interrupt(taskId);
    }

    @Override
    public PlanTaskSession resumeExecution(String taskId, String userFeedback) {
        return sceneTaskDispatcher.resume(taskId, userFeedback);
    }
}
