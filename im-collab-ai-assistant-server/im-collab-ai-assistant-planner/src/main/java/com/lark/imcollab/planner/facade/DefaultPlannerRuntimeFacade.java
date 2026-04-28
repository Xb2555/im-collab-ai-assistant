package com.lark.imcollab.planner.facade;

import com.lark.imcollab.common.facade.PlannerRuntimeFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Deprecated
@Component
public class DefaultPlannerRuntimeFacade implements PlannerRuntimeFacade {

    @Override
    public PlanTaskSession getSession(String taskId) {
        throw new UnsupportedOperationException("Use TaskRepository instead");
    }

    @Override
    public PlanTaskSession saveSession(PlanTaskSession session) {
        throw new UnsupportedOperationException("Use TaskRepository instead");
    }

    @Override
    public void publishEvent(String taskId, String status) {
        throw new UnsupportedOperationException("Use TaskEventRepository instead");
    }

    @Override
    public void publishEvent(String taskId, String status, RequireInput requireInput) {
        throw new UnsupportedOperationException("Use TaskEventRepository instead");
    }

    @Override
    public TaskResultEvaluation evaluate(TaskSubmissionResult submission) {
        throw new UnsupportedOperationException("Evaluation chain removed");
    }

    @Override
    public Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId) {
        return Optional.empty();
    }
}
