package com.lark.imcollab.app.planner.service;

import com.lark.imcollab.common.facade.DocumentArtifactIterationFacade;
import com.lark.imcollab.common.facade.ImTaskCommandFacade;
import com.lark.imcollab.common.model.dto.DocumentIterationApprovalRequest;
import com.lark.imcollab.common.model.enums.DocumentArtifactIterationStatus;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.vo.DocumentArtifactIterationResult;
import com.lark.imcollab.planner.exception.RetryNotAllowedException;
import com.lark.imcollab.planner.service.PlannerRetryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorAction;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecision;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PlannerCommandApplicationService {

    private final PlannerSupervisorGraphRunner graphRunner;
    private final TaskBridgeService taskBridgeService;
    private final PlannerRetryService plannerRetryService;
    private final ImTaskCommandFacade taskCommandFacade;
    private final TaskRuntimeService taskRuntimeService;
    private final PlannerSessionService sessionService;
    private final ObjectProvider<DocumentArtifactIterationFacade> documentArtifactIterationFacadeProvider;

    @Autowired
    public PlannerCommandApplicationService(
            PlannerSupervisorGraphRunner graphRunner,
            TaskBridgeService taskBridgeService,
            PlannerRetryService plannerRetryService,
            ImTaskCommandFacade taskCommandFacade,
            TaskRuntimeService taskRuntimeService,
            PlannerSessionService sessionService,
            ObjectProvider<DocumentArtifactIterationFacade> documentArtifactIterationFacadeProvider
    ) {
        this.graphRunner = graphRunner;
        this.taskBridgeService = taskBridgeService;
        this.plannerRetryService = plannerRetryService;
        this.taskCommandFacade = taskCommandFacade;
        this.taskRuntimeService = taskRuntimeService;
        this.sessionService = sessionService;
        this.documentArtifactIterationFacadeProvider = documentArtifactIterationFacadeProvider;
    }

    public PlanTaskSession resume(String taskId, String feedback, boolean replanFromRoot) {
        return resume(taskId, feedback, replanFromRoot, null);
    }

    public PlanTaskSession resume(String taskId, String feedback, boolean replanFromRoot, WorkspaceContext workspaceContext) {
        PlanTaskSession current = sessionService.get(taskId);
        TaskIntakeState intakeState = current.getIntakeState();
        if (hasPendingDocumentApproval(intakeState)) {
            return continueDocumentApproval(current, feedback, workspaceContext, false);
        }
        if (intakeState != null
                && intakeState.getPendingAdjustmentInstruction() != null
                && !intakeState.getPendingAdjustmentInstruction().isBlank()) {
            current.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
            sessionService.saveWithoutVersionChange(current);
            return graphRunner.run(
                    new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "resume completed task adjustment"),
                    taskId,
                    intakeState.getPendingAdjustmentInstruction() + "\n用户补充：" + feedback,
                    workspaceContext,
                    feedback
            );
        }
        taskRuntimeService.appendUserIntervention(taskId, feedback);
        PlannerSupervisorAction action = replanFromRoot
                ? PlannerSupervisorAction.PLAN_ADJUSTMENT
                : PlannerSupervisorAction.CLARIFICATION_REPLY;
        return graphRunner.run(
                new PlannerSupervisorDecision(action, replanFromRoot ? "resume as plan adjustment" : "clarification reply"),
                taskId,
                feedback,
                workspaceContext,
                feedback
        );
    }

    public PlanTaskSession replan(String taskId, String feedback) {
        return replan(taskId, feedback, null, null);
    }

    public PlanTaskSession replan(String taskId, String feedback, String artifactPolicy, String targetArtifactId) {
        String effectiveFeedback = appendCommandHints(feedback, artifactPolicy, targetArtifactId);
        PlanTaskSession session = graphRunner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "user requested plan adjustment"),
                taskId,
                effectiveFeedback,
                null,
                effectiveFeedback
        );
        taskBridgeService.ensureTask(session);
        return session;
    }

    private String appendCommandHints(String feedback, String artifactPolicy, String targetArtifactId) {
        StringBuilder builder = new StringBuilder(feedback == null ? "" : feedback.trim());
        if (artifactPolicy != null && !artifactPolicy.isBlank()) {
            builder.append("\n产物策略：").append(artifactPolicy.trim());
        }
        if (targetArtifactId != null && !targetArtifactId.isBlank()) {
            builder.append("\n目标产物ID：").append(targetArtifactId.trim());
        }
        return builder.toString().trim();
    }

    public PlanTaskSession confirmExecution(String taskId, PlanTaskSession currentSession) {
        if (hasPendingDocumentApproval(currentSession == null ? null : currentSession.getIntakeState())) {
            return continueDocumentApproval(currentSession, null, null, true);
        }
        return taskCommandFacade.confirmExecution(taskId);
    }

    public PlanTaskSession cancel(String taskId) {
        sessionService.markAborted(taskId, "User cancelled from GUI command");
        taskCommandFacade.cancelExecution(taskId);
        taskRuntimeService.projectPhaseTransition(taskId, PlanningPhaseEnum.ABORTED, TaskEventTypeEnum.TASK_CANCELLED);
        return sessionService.get(taskId);
    }

    public PlanTaskSession retryFailed(String taskId, PlanTaskSession currentSession, String feedback) {
        if (!plannerRetryService.isRetryable(taskId, currentSession)) {
            throw new RetryNotAllowedException("当前任务不是失败状态，不需要重试。");
        }
        taskRuntimeService.appendUserIntervention(taskId, feedback);
        return taskCommandFacade.retryExecution(taskId, feedback);
    }

    private PlanTaskSession continueDocumentApproval(
            PlanTaskSession current,
            String feedback,
            WorkspaceContext workspaceContext,
            boolean confirmed
    ) {
        TaskIntakeState intakeState = current == null ? null : current.getIntakeState();
        if (!hasPendingDocumentApproval(intakeState)) {
            return current;
        }
        DocumentArtifactIterationFacade facade = documentArtifactIterationFacadeProvider == null
                ? null
                : documentArtifactIterationFacadeProvider.getIfAvailable();
        if (facade == null) {
            return current;
        }
        String taskId = current.getTaskId();
        String action = resolveApprovalAction(feedback, confirmed);
        String operatorOpenId = workspaceContext != null && hasText(workspaceContext.getSenderOpenId())
                ? workspaceContext.getSenderOpenId()
                : current.getInputContext() == null ? null : current.getInputContext().getSenderOpenId();
        DocumentArtifactIterationResult result = facade.decide(
                intakeState.getPendingDocumentIterationTaskId(),
                intakeState.getPendingDocumentArtifactId(),
                intakeState.getPendingDocumentDocUrl(),
                DocumentIterationApprovalRequest.builder()
                        .action(action)
                        .feedback(feedback)
                        .build(),
                operatorOpenId
        );
        return applyDocumentApprovalResult(current, intakeState, result, feedback, taskId);
    }

    private PlanTaskSession applyDocumentApprovalResult(
            PlanTaskSession current,
            TaskIntakeState intakeState,
            DocumentArtifactIterationResult result,
            String feedback,
            String taskId
    ) {
        DocumentArtifactIterationStatus status = result == null ? DocumentArtifactIterationStatus.FAILED : result.getStatus();
        if (status == DocumentArtifactIterationStatus.COMPLETED) {
            clearPendingDocumentApproval(intakeState);
            intakeState.setAssistantReply(result.getSummary());
            intakeState.setPendingAdjustmentInstruction(null);
            current.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
            current.setTransitionReason("Completed DOC adjustment approval executed");
            sessionService.saveWithoutVersionChange(current);
            sessionService.publishEvent(taskId, "COMPLETED");
            return current;
        }
        if (status == DocumentArtifactIterationStatus.WAITING_APPROVAL) {
            intakeState.setAssistantReply(result.getSummary());
            intakeState.setPendingDocumentIterationTaskId(result.getTaskId());
            intakeState.setPendingDocumentArtifactId(result.getArtifactId());
            intakeState.setPendingDocumentDocUrl(result.getDocUrl());
            intakeState.setPendingDocumentApprovalSummary(result.getSummary());
            current.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
            current.setTransitionReason("Completed DOC adjustment approval still waiting");
            sessionService.saveWithoutVersionChange(current);
            sessionService.publishEvent(taskId, "ASK_USER");
            return current;
        }
        clearPendingDocumentApproval(intakeState);
        current.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
        intakeState.setAssistantReply(result == null ? feedback : result.getSummary());
        current.setTransitionReason("Completed DOC adjustment approval failed");
        sessionService.saveWithoutVersionChange(current);
        sessionService.publishEvent(taskId, "COMPLETED");
        return current;
    }

    private String resolveApprovalAction(String feedback, boolean confirmed) {
        if (confirmed) {
            return "APPROVE";
        }
        if (!hasText(feedback)) {
            return "APPROVE";
        }
        String normalized = feedback.trim().toLowerCase();
        if (normalized.contains("取消") || normalized.contains("不用") || normalized.contains("拒绝") || normalized.contains("算了")) {
            return "REJECT";
        }
        if (normalized.equals("确认") || normalized.equals("继续") || normalized.equals("执行") || normalized.equals("同意")) {
            return "APPROVE";
        }
        return "MODIFY";
    }

    private boolean hasPendingDocumentApproval(TaskIntakeState intakeState) {
        return intakeState != null
                && hasText(intakeState.getPendingDocumentIterationTaskId())
                && hasText(intakeState.getPendingDocumentApprovalMode());
    }

    private void clearPendingDocumentApproval(TaskIntakeState intakeState) {
        if (intakeState == null) {
            return;
        }
        intakeState.setPendingDocumentIterationTaskId(null);
        intakeState.setPendingDocumentArtifactId(null);
        intakeState.setPendingDocumentDocUrl(null);
        intakeState.setPendingDocumentApprovalSummary(null);
        intakeState.setPendingDocumentApprovalMode(null);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
