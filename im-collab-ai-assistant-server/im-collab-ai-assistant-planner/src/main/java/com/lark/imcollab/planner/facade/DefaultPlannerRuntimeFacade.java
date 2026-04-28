package com.lark.imcollab.planner.facade;

import com.lark.imcollab.common.facade.PlannerRuntimeFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskResultEvaluationService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DefaultPlannerRuntimeFacade implements PlannerRuntimeFacade {

    private final PlannerSessionService sessionService;
    private final TaskResultEvaluationService evaluationService;
    private final PlannerStateStore plannerStateStore;

    @Override
    public PlanTaskSession getSession(String taskId) {
        return sessionService.get(taskId);
    }

    @Override
    public PlanTaskSession saveSession(PlanTaskSession session) {
        return sessionService.save(session);
    }

    @Override
    public void publishEvent(String taskId, String status) {
        sessionService.publishEvent(taskId, status);
    }

    @Override
    public void publishEvent(String taskId, String status, RequireInput requireInput) {
        sessionService.publishEvent(taskId, status, requireInput);
    }

    @Override
    public TaskResultEvaluation evaluate(TaskSubmissionResult submission) {
        plannerStateStore.saveSubmission(submission);
        return evaluationService.evaluate(submission);
    }

    @Override
    public Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId) {
        return plannerStateStore.findSubmission(taskId, agentTaskId);
    }
}
