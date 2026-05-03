package com.lark.imcollab.planner.service;

import cn.hutool.core.util.StrUtil;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.config.PlannerAsyncProperties;
import com.lark.imcollab.planner.runtime.TaskRuntimeProjectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class AsyncPlannerService {

    private final PlannerConversationService plannerConversationService;
    private final PlannerSessionService sessionService;
    private final TaskRuntimeProjectionService projectionService;
    private final PlannerAsyncProperties asyncProperties;
    private final Executor plannerTaskExecutor;
    private final TaskIntakeService intakeService;
    private final Set<String> inFlightTasks = ConcurrentHashMap.newKeySet();

    public AsyncPlannerService(
            PlannerConversationService plannerConversationService,
            PlannerSessionService sessionService,
            TaskRuntimeProjectionService projectionService,
            PlannerAsyncProperties asyncProperties,
            @Qualifier("plannerTaskExecutor") Executor plannerTaskExecutor,
            TaskIntakeService intakeService
    ) {
        this.plannerConversationService = plannerConversationService;
        this.sessionService = sessionService;
        this.projectionService = projectionService;
        this.asyncProperties = asyncProperties;
        this.plannerTaskExecutor = plannerTaskExecutor;
        this.intakeService = intakeService;
    }

    public PlanTaskSession submitPlan(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String taskId,
            String userFeedback
    ) {
        String resolvedTaskId = StrUtil.isBlank(taskId) ? UUID.randomUUID().toString() : taskId.trim();
        TaskIntakeDecision earlyDecision = !StrUtil.isBlank(taskId) || intakeService == null
                ? null
                : intakeService.decide(null, rawInstruction, userFeedback, false);
        if (StrUtil.isBlank(taskId) && shouldShortCircuitWithoutTask(earlyDecision)) {
            return transientReplySession(resolvedTaskId, rawInstruction, workspaceContext, earlyDecision);
        }
        if (inFlightTasks.contains(resolvedTaskId)) {
            return sessionService.getOrCreate(resolvedTaskId);
        }
        PlanTaskSession session = initializeAcceptedSession(resolvedTaskId, rawInstruction);
        if (!asyncProperties.isEnabled()) {
            return plannerConversationService.handlePlanRequest(rawInstruction, workspaceContext, resolvedTaskId, userFeedback);
        }
        if (!inFlightTasks.add(resolvedTaskId)) {
            return sessionService.get(resolvedTaskId);
        }
        try {
            plannerTaskExecutor.execute(() -> runPlanning(resolvedTaskId, rawInstruction, workspaceContext, userFeedback));
        } catch (RejectedExecutionException exception) {
            inFlightTasks.remove(resolvedTaskId);
            return failAcceptedSession(resolvedTaskId, "Planner queue is full: " + exception.getMessage());
        }
        return session;
    }

    private boolean shouldShortCircuitWithoutTask(TaskIntakeDecision decision) {
        if (decision == null) {
            return false;
        }
        TaskIntakeTypeEnum type = decision.intakeType();
        return type == TaskIntakeTypeEnum.UNKNOWN
                || type == TaskIntakeTypeEnum.STATUS_QUERY
                || type == TaskIntakeTypeEnum.CANCEL_TASK
                || type == TaskIntakeTypeEnum.CONFIRM_ACTION;
    }

    private PlanTaskSession transientReplySession(
            String taskId,
            String rawInstruction,
            WorkspaceContext workspaceContext,
            TaskIntakeDecision decision
    ) {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId(taskId)
                .rawInstruction(rawInstruction == null ? null : rawInstruction.trim())
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .inputContext(TaskInputContext.builder()
                        .inputSource(workspaceContext == null ? null : workspaceContext.getInputSource())
                        .chatId(workspaceContext == null ? null : workspaceContext.getChatId())
                        .threadId(workspaceContext == null ? null : workspaceContext.getThreadId())
                        .messageId(workspaceContext == null ? null : workspaceContext.getMessageId())
                        .senderOpenId(workspaceContext == null ? null : workspaceContext.getSenderOpenId())
                        .chatType(workspaceContext == null ? null : workspaceContext.getChatType())
                        .build())
                .intakeState(TaskIntakeState.builder()
                        .intakeType(decision.intakeType())
                        .lastUserMessage(decision.effectiveInput())
                        .routingReason(decision.routingReason())
                        .assistantReply(decision.assistantReply())
                        .readOnlyView(decision.readOnlyView())
                        .build())
                .build();
        sessionService.saveWithoutVersionChange(session);
        return session;
    }

    private PlanTaskSession initializeAcceptedSession(String taskId, String rawInstruction) {
        PlanTaskSession session = sessionService.getOrCreate(taskId);
        if (session.getRawInstruction() == null && rawInstruction != null && !rawInstruction.isBlank()) {
            session.setRawInstruction(rawInstruction.trim());
        }
        if (session.getPlanningPhase() == null || session.getPlanningPhase() == PlanningPhaseEnum.INTAKE) {
            session.setPlanningPhase(PlanningPhaseEnum.INTAKE);
            session.setTransitionReason("Planner accepted async task");
            sessionService.saveWithoutVersionChange(session);
            projectionService.projectStage(session, TaskEventTypeEnum.INTAKE_ACCEPTED, "Planner accepted async task");
        }
        return session;
    }

    private void runPlanning(
            String taskId,
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String userFeedback
    ) {
        try {
            plannerConversationService.handlePlanRequest(rawInstruction, workspaceContext, taskId, userFeedback);
        } catch (Exception exception) {
            PlanTaskSession current = sessionService.get(taskId);
            TaskIntakeTypeEnum intakeType = current.getIntakeState() == null ? null : current.getIntakeState().getIntakeType();
            log.error(
                    "Async planner failed: taskId={}, thread={}, intakeType={}, rawInstruction={}, error={}",
                    taskId,
                    Thread.currentThread().getName(),
                    intakeType,
                    rawInstruction,
                    exception.getMessage(),
                    exception
            );
            failAcceptedSession(taskId, exception.getMessage());
        } finally {
            inFlightTasks.remove(taskId);
        }
    }

    private PlanTaskSession failAcceptedSession(String taskId, String reason) {
        PlanTaskSession session = sessionService.get(taskId);
        session.setPlanningPhase(PlanningPhaseEnum.FAILED);
        session.setTransitionReason(reason);
        sessionService.save(session);
        projectionService.projectStage(session, TaskEventTypeEnum.PLAN_FAILED, reason);
        sessionService.publishEvent(taskId, "FAILED");
        return session;
    }
}
