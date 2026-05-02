package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecision;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PlannerConversationService {

    private final TaskSessionResolver sessionResolver;
    private final TaskIntakeService intakeService;
    private final PlannerSessionService sessionService;
    private final TaskBridgeService taskBridgeService;
    private final PlannerConversationMemoryService memoryService;
    private final PlannerSupervisorGraphRunner graphRunner;

    public PlannerConversationService(
            TaskSessionResolver sessionResolver,
            TaskIntakeService intakeService,
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            PlannerConversationMemoryService memoryService,
            PlannerSupervisorGraphRunner graphRunner
    ) {
        this.sessionResolver = sessionResolver;
        this.intakeService = intakeService;
        this.sessionService = sessionService;
        this.taskBridgeService = taskBridgeService;
        this.memoryService = memoryService;
        this.graphRunner = graphRunner;
    }

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
        memoryService.appendUserTurn(
                session,
                intakeDecision.effectiveInput(),
                intakeDecision.intakeType(),
                workspaceContext == null ? null : workspaceContext.getInputSource());
        if (intakeDecision.assistantReply() != null && !intakeDecision.assistantReply().isBlank()) {
            memoryService.appendAssistantTurn(session, intakeDecision.assistantReply());
        }
        sessionService.saveWithoutVersionChange(session);
        sessionService.publishEvent(session.getTaskId(), "INTAKE_ACCEPTED");

        PlanTaskSession result = graphRunner.run(
                PlannerSupervisorDecision.fromIntake(intakeDecision.intakeType(), intakeDecision.routingReason()),
                session.getTaskId(),
                intakeDecision.effectiveInput(),
                workspaceContext,
                userFeedback
        );
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
                .routingReason(intakeDecision.routingReason())
                .assistantReply(intakeDecision.assistantReply())
                .readOnlyView(intakeDecision.readOnlyView())
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
