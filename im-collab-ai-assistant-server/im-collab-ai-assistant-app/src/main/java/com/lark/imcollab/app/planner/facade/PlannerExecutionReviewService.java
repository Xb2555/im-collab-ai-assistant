package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.facade.TaskUserNotificationFacade;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskResultEvaluationService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.Instant;

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
        if (hasUnfinishedSteps(snapshot)) {
            String reason = unfinishedStepReason(snapshot);
            markRuntimeFailed(snapshot, session, reason);
            updateSessionPhase(session, snapshot, null, reason);
            notifyUser(sessionService.get(taskId), snapshot, null);
            return;
        }
        TaskSubmissionResult submission = buildSubmission(taskId, snapshot);
        stateStore.saveSubmission(submission);
        TaskResultEvaluation evaluation = evaluationService.evaluate(submission);
        updateSessionPhase(session, snapshot, evaluation, null);
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
            TaskResultEvaluation evaluation,
            String overrideReason
    ) {
        if (session == null) {
            return;
        }
        PlanningPhaseEnum nextPhase = resolvePhase(snapshot, evaluation);
        session.setPlanningPhase(nextPhase);
        session.setTransitionReason(overrideReason != null && !overrideReason.isBlank()
                ? overrideReason
                : "Planner reviewed harness execution result: "
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

    private boolean hasUnfinishedSteps(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null || snapshot.getSteps() == null || snapshot.getSteps().isEmpty()) {
            return false;
        }
        return snapshot.getSteps().stream()
                .filter(step -> step != null && step.getStatus() != StepStatusEnum.SUPERSEDED)
                .anyMatch(step -> step.getStatus() != StepStatusEnum.COMPLETED
                        && step.getStatus() != StepStatusEnum.SKIPPED);
    }

    private String unfinishedStepReason(TaskRuntimeSnapshot snapshot) {
        List<String> remaining = snapshot == null || snapshot.getSteps() == null
                ? List.of()
                : snapshot.getSteps().stream()
                .filter(step -> step != null && step.getStatus() != StepStatusEnum.SUPERSEDED)
                .filter(step -> step.getStatus() != StepStatusEnum.COMPLETED
                        && step.getStatus() != StepStatusEnum.SKIPPED)
                .map(TaskStepRecord::getName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
        if (remaining.isEmpty()) {
            return "执行链路尚未完成全部计划步骤。";
        }
        return "当前执行链路只完成了部分步骤，剩余未完成：" + String.join("、", remaining);
    }

    private void markRuntimeFailed(TaskRuntimeSnapshot snapshot, PlanTaskSession session, String reason) {
        if (snapshot == null || snapshot.getTask() == null) {
            return;
        }
        TaskRecord task = snapshot.getTask();
        int progress = computeProgress(snapshot.getSteps());
        task.setStatus(TaskStatusEnum.FAILED);
        task.setCurrentStage(TaskStatusEnum.FAILED.name());
        task.setProgress(progress);
        task.setNeedUserAction(false);
        task.setUpdatedAt(Instant.now());
        stateStore.saveTask(task);
        stateStore.appendRuntimeEvent(TaskEventRecord.builder()
                .eventId(java.util.UUID.randomUUID().toString())
                .taskId(task.getTaskId())
                .type(TaskEventTypeEnum.TASK_FAILED)
                .payloadJson(toJson(reason))
                .version(session == null ? task.getVersion() : session.getVersion())
                .createdAt(Instant.now())
                .build());
    }

    private int computeProgress(List<TaskStepRecord> steps) {
        if (steps == null || steps.isEmpty()) {
            return 0;
        }
        long total = steps.stream()
                .filter(step -> step != null && step.getStatus() != StepStatusEnum.SUPERSEDED)
                .count();
        if (total == 0) {
            return 0;
        }
        long completed = steps.stream()
                .filter(step -> step != null && step.getStatus() == StepStatusEnum.COMPLETED)
                .count();
        return (int) Math.round((completed * 100.0d) / total);
    }

    private String toJson(String reason) {
        return reason == null ? "null" : "\"" + reason.replace("\"", "\\\"") + "\"";
    }
}
