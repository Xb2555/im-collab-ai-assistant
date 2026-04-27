package com.lark.imcollab.store.planner;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEvent;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;

import java.util.List;
import java.util.Optional;

public interface PlannerStateStore {

    void saveSession(PlanTaskSession session);

    Optional<PlanTaskSession> findSession(String taskId);

    void appendEvent(TaskEvent event);

    List<String> getEventJsonList(String taskId);

    void saveSubmission(TaskSubmissionResult submission);

    Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId);

    void saveEvaluation(TaskResultEvaluation evaluation);

    Optional<TaskResultEvaluation> findEvaluation(String taskId, String agentTaskId);
}
