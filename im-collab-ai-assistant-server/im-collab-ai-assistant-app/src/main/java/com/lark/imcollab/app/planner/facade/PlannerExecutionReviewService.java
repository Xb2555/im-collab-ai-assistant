package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.facade.TaskUserNotificationFacade;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskResultEvaluationService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.planner.service.ConversationTaskStateService;
import com.lark.imcollab.planner.exception.VersionConflictException;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.LinkedHashMap;

@Service
public class PlannerExecutionReviewService {

    private static final Logger log = LoggerFactory.getLogger(PlannerExecutionReviewService.class);
    private static final String AGENT_TASK_ID = "harness";

    private final PlannerSessionService sessionService;
    private final TaskRuntimeService taskRuntimeService;
    private final TaskResultEvaluationService evaluationService;
    private final ConversationTaskStateService conversationTaskStateService;
    private final PlannerStateStore stateStore;
    private final List<TaskUserNotificationFacade> notificationFacades;

    public PlannerExecutionReviewService(
            PlannerSessionService sessionService,
            TaskRuntimeService taskRuntimeService,
            TaskResultEvaluationService evaluationService,
            ConversationTaskStateService conversationTaskStateService,
            PlannerStateStore stateStore,
            List<TaskUserNotificationFacade> notificationFacades
    ) {
        this.sessionService = sessionService;
        this.taskRuntimeService = taskRuntimeService;
        this.evaluationService = evaluationService;
        this.conversationTaskStateService = conversationTaskStateService;
        this.stateStore = stateStore;
        this.notificationFacades = notificationFacades == null ? List.of() : notificationFacades;
    }

    public void reviewAndNotify(String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        if (!isReviewableExecutingSession(session)) {
            log.info("Skip execution review because session already left executing: taskId={}, phase={}",
                    taskId, session == null ? null : session.getPlanningPhase());
            return;
        }
        TaskRuntimeSnapshot snapshot = taskRuntimeService.getSnapshot(taskId);
        if (isWaitingForHumanApproval(snapshot)) {
            session = sessionService.get(taskId);
            if (!isReviewableExecutingSession(session)) {
                log.info("Skip human-review projection because session already changed: taskId={}, phase={}",
                        taskId, session == null ? null : session.getPlanningPhase());
                return;
            }
            String reason = latestApprovalReason(taskId);
            TaskResultEvaluation evaluation = humanReviewEvaluation(taskId, reason);
            stateStore.saveEvaluation(evaluation);
            clearPendingFollowUps(session);
            updateSessionForHumanReview(session, reason);
            notifyUser(sessionService.get(taskId), snapshot, evaluation);
            return;
        }
        if (hasUnfinishedSteps(snapshot)) {
            session = sessionService.get(taskId);
            if (!isReviewableExecutingSession(session)) {
                log.info("Skip unfinished-step failure projection because session already changed: taskId={}, phase={}",
                        taskId, session == null ? null : session.getPlanningPhase());
                return;
            }
            String reason = unfinishedStepReason(snapshot);
            markRuntimeFailed(snapshot, session, reason);
            clearPendingFollowUps(session);
            updateSessionPhase(session, snapshot, null, reason);
            notifyUser(sessionService.get(taskId), snapshot, null);
            return;
        }
        TaskSubmissionResult submission = buildSubmission(taskId, snapshot);
        stateStore.saveSubmission(submission);
        TaskResultEvaluation evaluation = evaluationService.evaluate(submission, session, snapshot);
        syncPendingFollowUps(session, evaluation);
        updateSessionPhase(session, snapshot, evaluation, null);
        notifyUser(sessionService.get(taskId), snapshot, evaluation);
    }

    private void syncPendingFollowUps(PlanTaskSession session, TaskResultEvaluation evaluation) {
        if (conversationTaskStateService == null || session == null || evaluation == null) {
            return;
        }
        if (evaluation.getVerdict() == ResultVerdictEnum.PASS) {
            conversationTaskStateService.syncPendingFollowUpRecommendations(session, evaluation);
            return;
        }
        clearPendingFollowUps(session);
    }

    private void clearPendingFollowUps(PlanTaskSession session) {
        if (conversationTaskStateService == null || session == null || session.getIntakeState() == null) {
            return;
        }
        conversationTaskStateService.clearPendingFollowUpRecommendations(session.getIntakeState().getContinuationKey());
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
        if (nextPhase == PlanningPhaseEnum.COMPLETED) {
            syncCompletedPlanCards(session, snapshot);
        }
        session.setPlanningPhase(nextPhase);
        String transitionReason = overrideReason != null && !overrideReason.isBlank()
                ? overrideReason
                : "Planner reviewed harness execution result: "
                + (evaluation == null ? "UNKNOWN" : evaluation.getVerdict());
        session.setTransitionReason(transitionReason);
        try {
            sessionService.save(session);
        } catch (VersionConflictException conflict) {
            log.warn("Execution review session save conflicted, retrying with latest session: taskId={}", session.getTaskId(), conflict);
            PlanTaskSession latest = sessionService.get(session.getTaskId());
            if (nextPhase == PlanningPhaseEnum.COMPLETED) {
                syncCompletedPlanCards(latest, snapshot);
            }
            latest.setPlanningPhase(nextPhase);
            latest.setTransitionReason(transitionReason);
            sessionService.saveWithoutVersionChange(latest);
        }
        taskRuntimeService.syncTaskState(session.getTaskId(), nextPhase);
        sessionService.publishEvent(session.getTaskId(), nextPhase.name());
    }

    private void syncCompletedPlanCards(PlanTaskSession session, TaskRuntimeSnapshot snapshot) {
        if (session == null || snapshot == null || snapshot.getSteps() == null || snapshot.getSteps().isEmpty()) {
            return;
        }
        Map<String, TaskStepRecord> stepsById = new LinkedHashMap<>();
        for (TaskStepRecord step : snapshot.getSteps()) {
            if (step == null || step.getStepId() == null || step.getStepId().isBlank()) {
                continue;
            }
            stepsById.put(step.getStepId(), step);
        }
        syncCompletedPlanCards(session.getPlanCards(), stepsById);
        PlanBlueprint blueprint = session.getPlanBlueprint();
        if (blueprint != null) {
            syncCompletedPlanCards(blueprint.getPlanCards(), stepsById);
        }
    }

    private void syncCompletedPlanCards(List<UserPlanCard> cards, Map<String, TaskStepRecord> stepsById) {
        if (cards == null || cards.isEmpty() || stepsById == null || stepsById.isEmpty()) {
            return;
        }
        for (UserPlanCard card : cards) {
            if (card == null || card.getCardId() == null || card.getCardId().isBlank()) {
                continue;
            }
            TaskStepRecord step = stepsById.get(card.getCardId());
            if (step == null || step.getStatus() == null) {
                continue;
            }
            card.setStatus(step.getStatus().name());
            card.setProgress(step.getProgress());
        }
    }

    private void updateSessionForHumanReview(PlanTaskSession session, String reason) {
        if (session == null) {
            return;
        }
        session.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        session.setTransitionReason(firstNonBlank(reason, "Harness execution is waiting for human review"));
        sessionService.save(session);
        sessionService.publishEvent(session.getTaskId(), PlanningPhaseEnum.ASK_USER.name());
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

    private boolean isWaitingForHumanApproval(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        if (snapshot.getTask() != null && snapshot.getTask().getStatus() == TaskStatusEnum.WAITING_APPROVAL) {
            return true;
        }
        return snapshot.getSteps() != null
                && snapshot.getSteps().stream()
                .anyMatch(step -> step != null && step.getStatus() == StepStatusEnum.WAITING_APPROVAL);
    }

    private TaskResultEvaluation humanReviewEvaluation(String taskId, String reason) {
        return TaskResultEvaluation.builder()
                .taskId(taskId)
                .agentTaskId(AGENT_TASK_ID)
                .verdict(ResultVerdictEnum.HUMAN_REVIEW)
                .issues(firstNonBlank(reason) == null ? List.of("需要用户确认执行产物。") : List.of(reason))
                .suggestions(List.of("请确认当前产物是否符合预期，或补充修改意见后继续。"))
                .build();
    }

    private String latestApprovalReason(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        List<TaskEventRecord> events = stateStore.findRuntimeEventsByTaskId(taskId);
        if (events == null || events.isEmpty()) {
            return null;
        }
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskEventRecord event = events.get(index);
            if (event != null && event.getType() == TaskEventTypeEnum.PLAN_APPROVAL_REQUIRED) {
                return decodePayload(event.getPayloadJson());
            }
        }
        return null;
    }

    private String decodePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank() || "null".equals(payloadJson.trim())) {
            return null;
        }
        String value = payloadJson.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t");
        }
        return value.isBlank() ? null : value;
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

    private boolean isReviewableExecutingSession(PlanTaskSession session) {
        return session != null && session.getPlanningPhase() == PlanningPhaseEnum.EXECUTING;
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
