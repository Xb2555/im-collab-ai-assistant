package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PendingInteractionTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.util.ExecutionCommandGuard;
import com.lark.imcollab.planner.clarification.ClarificationService;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ClarificationNodeService {

    private final PlannerSessionService sessionService;
    private final ClarificationService clarificationService;
    private final PlannerConversationMemoryService memoryService;
    private final PlanningNodeService planningNodeService;
    private final ReplanNodeService replanNodeService;
    private final TaskBridgeService taskBridgeService;
    private final TaskRuntimeService taskRuntimeService;
    private final PlannerExecutionTool executionTool;

    @Autowired
    public ClarificationNodeService(
            PlannerSessionService sessionService,
            ClarificationService clarificationService,
            PlannerConversationMemoryService memoryService,
            PlanningNodeService planningNodeService,
            ReplanNodeService replanNodeService,
            TaskBridgeService taskBridgeService,
            TaskRuntimeService taskRuntimeService
    ) {
        this(sessionService, clarificationService, memoryService, planningNodeService, replanNodeService,
                taskBridgeService, taskRuntimeService, null);
    }

    public ClarificationNodeService(
            PlannerSessionService sessionService,
            ClarificationService clarificationService,
            PlannerConversationMemoryService memoryService,
            PlanningNodeService planningNodeService,
            ReplanNodeService replanNodeService,
            TaskBridgeService taskBridgeService,
            TaskRuntimeService taskRuntimeService,
            PlannerExecutionTool executionTool
    ) {
        this.sessionService = sessionService;
        this.clarificationService = clarificationService;
        this.memoryService = memoryService;
        this.planningNodeService = planningNodeService;
        this.replanNodeService = replanNodeService;
        this.taskBridgeService = taskBridgeService;
        this.taskRuntimeService = taskRuntimeService;
        this.executionTool = executionTool;
    }

    public PlanTaskSession resume(String taskId, String feedback) {
        return resume(taskId, feedback, null);
    }

    public PlanTaskSession resume(String taskId, String feedback, WorkspaceContext workspaceContext) {
        PlanTaskSession session = sessionService.get(taskId);
        clarificationService.absorbAnswer(session, feedback);
        session.setAborted(false);
        session.setTransitionReason("Resume: " + feedback);
        memoryService.appendUserTurnIfLatestDifferent(session, feedback, null, "CLARIFICATION_REPLY");
        if (isExecutingPlanAdjustmentClarification(session)) {
            if (shouldResumeOriginalExecution(session, feedback)) {
                return resumeOriginalExecution(session, feedback);
            }
            session.setPlanningPhase(PlanningPhaseEnum.REPLANNING);
            session.setTransitionReason("Resume executing plan adjustment: " + feedback);
            sessionService.saveWithoutVersionChange(session);
            sessionService.publishEvent(taskId, "RESUMED");
            PlanTaskSession replanned = replanNodeService.replan(taskId, feedback, workspaceContext);
            normalizeExecutingPlanAdjustmentResult(replanned, feedback);
            if (replanned != null && replanned.getPlanningPhase() == PlanningPhaseEnum.PLAN_READY) {
                if (taskBridgeService != null) {
                    taskBridgeService.ensureTask(replanned);
                }
                if (taskRuntimeService != null) {
                    taskRuntimeService.reconcilePlanReadyProjection(replanned, TaskEventTypeEnum.PLAN_ADJUSTED);
                }
            }
            return replanned;
        }
        sessionService.saveWithoutVersionChange(session);
        sessionService.publishEvent(taskId, "RESUMED");
        return planningNodeService.plan(taskId, effectiveInstruction(session, feedback), workspaceContext, feedback);
    }

    private void normalizeExecutingPlanAdjustmentResult(PlanTaskSession session, String feedback) {
        if (session == null) {
            return;
        }
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        intakeState.setAssistantReply(null);
        if (session.getPlanningPhase() == PlanningPhaseEnum.PLAN_READY) {
            intakeState.setPendingInteractionType(null);
            intakeState.setPendingAdjustmentInstruction(null);
        }
        session.setIntakeState(intakeState);
        session.setTransitionReason("Resume executing plan adjustment: " + feedback);
        sessionService.saveWithoutVersionChange(session);
    }

    private boolean isExecutingPlanAdjustmentClarification(PlanTaskSession session) {
        if (session == null || session.getPlanningPhase() != PlanningPhaseEnum.ASK_USER) {
            return false;
        }
        TaskIntakeState intakeState = session.getIntakeState();
        return intakeState != null
                && intakeState.getPendingInteractionType() == PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT;
    }

    private boolean shouldResumeOriginalExecution(PlanTaskSession session, String feedback) {
        TaskIntakeState intakeState = session == null ? null : session.getIntakeState();
        return intakeState != null
                && intakeState.isResumeOriginalExecutionAvailable()
                && ExecutionCommandGuard.isExplicitExecutionRequest(feedback);
    }

    private PlanTaskSession resumeOriginalExecution(PlanTaskSession session, String feedback) {
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setPendingInteractionType(null);
        intakeState.setPendingAdjustmentInstruction(null);
        intakeState.setResumeOriginalExecutionAvailable(false);
        intakeState.setIntakeType(TaskIntakeTypeEnum.CONFIRM_ACTION);
        intakeState.setAssistantReply("好的，继续按原执行流程推进。");
        session.setIntakeState(intakeState);
        session.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
        session.setTransitionReason("Resume original execution from interrupt clarification: " + feedback);
        sessionService.saveWithoutVersionChange(session);
        sessionService.publishEvent(session.getTaskId(), PlanningPhaseEnum.EXECUTING.name());
        if (taskRuntimeService != null) {
            taskRuntimeService.projectPhaseTransition(
                    session.getTaskId(),
                    PlanningPhaseEnum.EXECUTING,
                    TaskEventTypeEnum.USER_INTERVENTION
            );
        }
        if (executionTool != null) {
            executionTool.confirmExecution(session.getTaskId());
        }
        memoryService.appendAssistantTurn(session, intakeState.getAssistantReply());
        return session;
    }

    private String effectiveInstruction(PlanTaskSession session, String fallback) {
        if (session == null) {
            return fallback;
        }
        return firstNonBlank(session.getClarifiedInstruction(), session.getRawInstruction(), fallback);
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
