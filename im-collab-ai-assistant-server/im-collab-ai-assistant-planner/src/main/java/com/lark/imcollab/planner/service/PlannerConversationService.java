package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PlannerConversationService {

    private final TaskSessionResolver sessionResolver;
    private final TaskIntakeService intakeService;
    private final PlannerSessionService sessionService;
    private final SupervisorPlannerService supervisorPlannerService;
    private final TaskBridgeService taskBridgeService;

    public PlanTaskSession handlePlanRequest(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String taskId,
            String userFeedback
    ) {
        TaskSessionResolution resolution = sessionResolver.resolve(taskId, workspaceContext);
        PlanTaskSession session = sessionService.getOrCreate(resolution.taskId());
        sessionResolver.bindConversation(resolution);

        TaskIntakeDecision intakeDecision = intakeService.decide(
                session,
                rawInstruction,
                userFeedback,
                resolution.existingSession()
        );

        updateSessionEnvelope(session, workspaceContext, intakeDecision, resolution);
        sessionService.save(session);
        sessionService.publishEvent(session.getTaskId(), "INTAKE_ACCEPTED");

        PlanTaskSession result = switch (intakeDecision.intakeType()) {
            case STATUS_QUERY -> sessionService.get(session.getTaskId());
            case CANCEL_TASK -> {
                sessionService.markAborted(session.getTaskId(), "User cancelled from conversation: " + intakeDecision.effectiveInput());
                yield sessionService.get(session.getTaskId());
            }
            case CLARIFICATION_REPLY -> supervisorPlannerService.resume(session.getTaskId(), intakeDecision.effectiveInput(), false);
            case NEW_TASK -> supervisorPlannerService.plan(
                    intakeDecision.effectiveInput(),
                    workspaceContext,
                    session.getTaskId(),
                    userFeedback
            );
            case PLAN_ADJUSTMENT -> supervisorPlannerService.adjustPlan(
                    session.getTaskId(),
                    intakeDecision.effectiveInput(),
                    workspaceContext
            );
        };
        taskBridgeService.ensureTask(result);
        return result;
    }

    private void updateSessionEnvelope(
            PlanTaskSession session,
            WorkspaceContext workspaceContext,
            TaskIntakeDecision intakeDecision,
            TaskSessionResolution resolution
    ) {
        if (!resolution.existingSession() && session.getRawInstruction() == null) {
            session.setRawInstruction(intakeDecision.effectiveInput());
        }
        session.setInputContext(TaskInputContext.builder()
                .inputSource(workspaceContext == null ? null : workspaceContext.getInputSource())
                .chatId(workspaceContext == null ? null : workspaceContext.getChatId())
                .threadId(workspaceContext == null ? null : workspaceContext.getThreadId())
                .messageId(workspaceContext == null ? null : workspaceContext.getMessageId())
                .senderOpenId(workspaceContext == null ? null : workspaceContext.getSenderOpenId())
                .chatType(workspaceContext == null ? null : workspaceContext.getChatType())
                .build());
        session.setIntakeState(TaskIntakeState.builder()
                .intakeType(intakeDecision.intakeType())
                .continuedConversation(resolution.existingSession())
                .continuationKey(resolution.continuationKey())
                .lastUserMessage(intakeDecision.effectiveInput())
                .lastInputAt(workspaceContext == null ? null : workspaceContext.getTimeRange())
                .build());
        if (session.getScenarioPath() == null || session.getScenarioPath().isEmpty()) {
            session.setScenarioPath(List.of(ScenarioCodeEnum.A_IM, ScenarioCodeEnum.B_PLANNING));
        }
        if (session.getPlanningPhase() == null || !resolution.existingSession()) {
            session.setPlanningPhase(PlanningPhaseEnum.INTAKE);
        }
        session.setTransitionReason("Scenario A intake accepted");
    }
}
