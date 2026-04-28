package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;

import java.util.Optional;

/**
 * @deprecated replaced by {@link PlannerFacade} and domain model in common.domain
 */
@Deprecated
public interface PlannerRuntimeFacade {

    PlanTaskSession getSession(String taskId);

    PlanTaskSession saveSession(PlanTaskSession session);

    void publishEvent(String taskId, String status);

    void publishEvent(String taskId, String status, RequireInput requireInput);

    TaskResultEvaluation evaluate(TaskSubmissionResult submission);

    Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId);
}
