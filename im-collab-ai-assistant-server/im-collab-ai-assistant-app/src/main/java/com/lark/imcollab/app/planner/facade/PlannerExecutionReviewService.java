package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.facade.TaskUserNotificationFacade;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskResultEvaluationService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PlannerExecutionReviewService {

    private static final Logger log = LoggerFactory.getLogger(PlannerExecutionReviewService.class);
    private static final String AGENT_TASK_ID = "harness";

    private final PlannerSessionService sessionService;
    private final TaskRuntimeService taskRuntimeService;
    private final TaskResultEvaluationService evaluationService;
    private final PlannerStateStore stateStore;
    private final List<TaskUserNotificationFacade> notificationFacades;

    public PlannerExecutionReviewService(
            PlannerSessionService sessionService,
            TaskRuntimeService taskRuntimeService,
            TaskResultEvaluationService evaluationService,
            PlannerStateStore stateStore,
            List<TaskUserNotificationFacade> notificationFacades
    ) {
        this.sessionService = sessionService;
        this.taskRuntimeService = taskRuntimeService;
        this.evaluationService = evaluationService;
        this.stateStore = stateStore;
        this.notificationFacades = notificationFacades == null ? List.of() : notificationFacades;
    }

    public void reviewAndNotify(String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        TaskRuntimeSnapshot snapshot = taskRuntimeService.getSnapshot(taskId);
        TaskSubmissionResult submission = buildSubmission(taskId, snapshot);
        stateStore.saveSubmission(submission);
        TaskResultEvaluation evaluation = evaluationService.evaluate(submission);
        updateSessionPhase(session, snapshot, evaluation);
        notifyUser(sessionService.get(taskId), snapshot, evaluation);
    }

    private TaskSubmissionResult buildSubmission(String taskId, TaskRuntimeSnapshot snapshot) {
        List<ArtifactRecord> artifacts = snapshot == null || snapshot.getArtifacts() == null
                ? List.of()
                : snapshot.getArtifacts();
        String status = snapshot != null
                && snapshot.getTask() != null
                && snapshot.getTask().getStatus() == TaskStatusEnum.FAILED
                ? "FAILED"
                : "COMPLETED";
        return TaskSubmissionResult.builder()
                .taskId(taskId)
                .agentTaskId(AGENT_TASK_ID)
                .status(status)
                .artifactRefs(artifacts.stream().map(ArtifactRecord::getArtifactId).toList())
                .rawOutput(summarizeArtifacts(artifacts))
                .errorMessage("FAILED".equals(status) ? "Harness execution failed" : null)
                .build();
    }

    private void updateSessionPhase(
            PlanTaskSession session,
            TaskRuntimeSnapshot snapshot,
            TaskResultEvaluation evaluation
    ) {
        if (session == null) {
            return;
        }
        PlanningPhaseEnum nextPhase = resolvePhase(snapshot, evaluation);
        session.setPlanningPhase(nextPhase);
        session.setTransitionReason("Planner reviewed harness execution result: "
                + (evaluation == null ? "UNKNOWN" : evaluation.getVerdict()));
        sessionService.save(session);
        sessionService.publishEvent(session.getTaskId(), nextPhase.name());
    }

    private PlanningPhaseEnum resolvePhase(TaskRuntimeSnapshot snapshot, TaskResultEvaluation evaluation) {
        if (snapshot != null && snapshot.getTask() != null && snapshot.getTask().getStatus() == TaskStatusEnum.FAILED) {
            return PlanningPhaseEnum.FAILED;
        }
        if (evaluation != null && evaluation.getVerdict() != null) {
            return switch (evaluation.getVerdict()) {
                case PASS -> PlanningPhaseEnum.COMPLETED;
                case RETRY -> PlanningPhaseEnum.FAILED;
                case HUMAN_REVIEW -> PlanningPhaseEnum.ASK_USER;
            };
        }
        return PlanningPhaseEnum.FAILED;
    }

    private void notifyUser(
            PlanTaskSession session,
            TaskRuntimeSnapshot snapshot,
            TaskResultEvaluation evaluation
    ) {
        for (TaskUserNotificationFacade notificationFacade : notificationFacades) {
            try {
                notificationFacade.notifyExecutionReviewed(session, snapshot, evaluation);
            } catch (RuntimeException exception) {
                log.warn("Failed to notify user after planner review: taskId={}",
                        session == null ? null : session.getTaskId(), exception);
            }
        }
    }

    private String summarizeArtifacts(List<ArtifactRecord> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return null;
        }
        return artifacts.stream()
                .map(artifact -> artifact.getTitle() == null ? artifact.getArtifactId() : artifact.getTitle())
                .toList()
                .toString();
    }
}
