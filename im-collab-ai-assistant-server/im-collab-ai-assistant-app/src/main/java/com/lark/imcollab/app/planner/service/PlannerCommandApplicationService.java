package com.lark.imcollab.app.planner.service;

import com.lark.imcollab.common.facade.ImTaskCommandFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.planner.exception.RetryNotAllowedException;
import com.lark.imcollab.planner.service.PlannerRetryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorAction;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecision;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
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

    @Autowired
    public PlannerCommandApplicationService(
            PlannerSupervisorGraphRunner graphRunner,
            TaskBridgeService taskBridgeService,
            PlannerRetryService plannerRetryService,
            ImTaskCommandFacade taskCommandFacade,
            TaskRuntimeService taskRuntimeService,
            PlannerSessionService sessionService
    ) {
        this.graphRunner = graphRunner;
        this.taskBridgeService = taskBridgeService;
        this.plannerRetryService = plannerRetryService;
        this.taskCommandFacade = taskCommandFacade;
        this.taskRuntimeService = taskRuntimeService;
        this.sessionService = sessionService;
    }

    public PlanTaskSession resume(String taskId, String feedback, boolean replanFromRoot) {
        return resume(taskId, feedback, replanFromRoot, null);
    }

    public PlanTaskSession resume(String taskId, String feedback, boolean replanFromRoot, WorkspaceContext workspaceContext) {
        PlanTaskSession current = sessionService.get(taskId);
        TaskIntakeState intakeState = current.getIntakeState();
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
}
