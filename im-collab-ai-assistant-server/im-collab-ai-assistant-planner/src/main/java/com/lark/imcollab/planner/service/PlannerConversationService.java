package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
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
        PlanTaskSession session = resolution.existingSession()
                ? sessionService.get(resolution.taskId())
                : transientSession(resolution.taskId(), workspaceContext);

        TaskIntakeDecision intakeDecision = intakeService.decide(
                session,
                rawInstruction,
                userFeedback,
                resolution.existingSession()
        );
        String userInput = firstText(userFeedback, rawInstruction);
        String graphInstruction = userInput.isBlank() ? intakeDecision.effectiveInput() : userInput;
        if (shouldShortCircuitWithoutTask(resolution, intakeDecision)) {
            updateSessionEnvelope(session, workspaceContext, intakeDecision, resolution, graphInstruction);
            return session;
        }
        if (!resolution.existingSession()) {
            session = sessionService.getOrCreate(resolution.taskId());
        }
        if (shouldBindConversation(resolution, intakeDecision)) {
            sessionResolver.bindConversation(resolution);
        }

        updateSessionEnvelope(session, workspaceContext, intakeDecision, resolution, graphInstruction);
        memoryService.appendUserTurn(
                session,
                graphInstruction,
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
                graphInstruction,
                workspaceContext,
                userFeedback
        );
        taskBridgeService.ensureTask(result);
        return result;
    }

    private boolean shouldShortCircuitWithoutTask(TaskSessionResolution resolution, TaskIntakeDecision intakeDecision) {
        if (resolution == null || resolution.existingSession() || intakeDecision == null) {
            return false;
        }
        TaskIntakeTypeEnum type = intakeDecision.intakeType();
        return type == TaskIntakeTypeEnum.UNKNOWN
                || type == TaskIntakeTypeEnum.STATUS_QUERY
                || type == TaskIntakeTypeEnum.CANCEL_TASK
                || type == TaskIntakeTypeEnum.CONFIRM_ACTION;
    }

    private boolean shouldBindConversation(TaskSessionResolution resolution, TaskIntakeDecision intakeDecision) {
        if (resolution == null || intakeDecision == null) {
            return false;
        }
        if (resolution.existingSession()) {
            return true;
        }
        return switch (intakeDecision.intakeType()) {
            case STATUS_QUERY, UNKNOWN, CANCEL_TASK, CONFIRM_ACTION -> false;
            default -> true;
        };
    }

    private PlanTaskSession transientSession(String taskId, WorkspaceContext workspaceContext) {
        return PlanTaskSession.builder()
                .taskId(taskId)
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .planScore(0)
                .aborted(false)
                .turnCount(0)
                .scenarioPath(List.of(ScenarioCodeEnum.A_IM, ScenarioCodeEnum.B_PLANNING))
                .build();
    }

    private void updateSessionEnvelope(
            PlanTaskSession session,
            WorkspaceContext workspaceContext,
            TaskIntakeDecision intakeDecision,
            TaskSessionResolution resolution,
            String userInput
    ) {
        if (!resolution.existingSession() && session.getRawInstruction() == null) {
            session.setRawInstruction(firstText(userInput, intakeDecision.effectiveInput()));
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
                .lastUserMessage(firstText(userInput, intakeDecision.effectiveInput()))
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

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }
}
