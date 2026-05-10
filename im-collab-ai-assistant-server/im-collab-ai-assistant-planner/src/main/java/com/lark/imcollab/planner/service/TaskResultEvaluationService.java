package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import com.lark.imcollab.store.planner.PlannerStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskResultEvaluationService {

    private final PlannerStateStore repository;
    private final PlannerSessionService sessionService;
    private final TaskNextStepRecommendationService recommendationService;

    public TaskResultEvaluation evaluate(TaskSubmissionResult submission) {
        return evaluate(submission, null, null);
    }

    public TaskResultEvaluation evaluate(
            TaskSubmissionResult submission,
            PlanTaskSession session,
            TaskRuntimeSnapshot snapshot
    ) {
        ResultVerdictEnum verdict = applyGates(submission);
        TaskResultEvaluation evaluation = TaskResultEvaluation.builder()
                .taskId(submission.getTaskId())
                .agentTaskId(submission.getAgentTaskId())
                .resultScore(verdict == ResultVerdictEnum.PASS ? 100 : 0)
                .verdict(verdict)
                .issues(verdict == ResultVerdictEnum.HUMAN_REVIEW
                        ? List.of("Action gate: requires human confirmation before publishing")
                        : List.of())
                .suggestions(List.of())
                .nextStepRecommendations(verdict == ResultVerdictEnum.PASS
                        ? recommendationService.recommend(session, snapshot)
                        : List.of())
                .build();
        repository.saveEvaluation(evaluation);
        sessionService.publishEvent(submission.getTaskId(), verdict.name());
        return evaluation;
    }

    private ResultVerdictEnum applyGates(TaskSubmissionResult submission) {
        if ("FAILED".equalsIgnoreCase(submission.getStatus())) {
            return ResultVerdictEnum.RETRY;
        }
        if (submission.getArtifactRefs() == null || submission.getArtifactRefs().isEmpty()) {
            return ResultVerdictEnum.HUMAN_REVIEW;
        }
        return ResultVerdictEnum.PASS;
    }
}
