package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.facade.ImTaskCommandFacade;
import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import com.lark.imcollab.gateway.im.event.LarkMessageEvent;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public class LoggingLarkInboundMessageDispatcher implements LarkInboundMessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingLarkInboundMessageDispatcher.class);
    private final PlannerPlanFacade plannerPlanFacade;
    private final ImTaskCommandFacade taskCommandFacade;
    private final LarkMessageReplyTool replyTool;
    private final LarkIMMessageStreamService streamService;
    private final LarkOutboundMessageRetryService retryService;
    private final LarkIMTaskReplyFormatter replyFormatter;
    private final DocRefExtractionService docRefExtractionService;
    private final Executor plannerDispatchExecutor;

    public LoggingLarkInboundMessageDispatcher(PlannerPlanFacade plannerPlanFacade) {
        this(plannerPlanFacade, null, null, null, null, new LarkIMTaskReplyFormatter(),
                new DocRefExtractionService(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public LoggingLarkInboundMessageDispatcher(
            PlannerPlanFacade plannerPlanFacade,
            ImTaskCommandFacade taskCommandFacade,
            LarkMessageReplyTool replyTool,
            LarkIMMessageStreamService streamService,
            LarkOutboundMessageRetryService retryService,
            LarkIMTaskReplyFormatter replyFormatter,
            DocRefExtractionService docRefExtractionService
    ) {
        this(plannerPlanFacade, taskCommandFacade, replyTool, streamService, retryService, replyFormatter,
                docRefExtractionService, Runnable::run);
    }

    @Autowired
    public LoggingLarkInboundMessageDispatcher(
            PlannerPlanFacade plannerPlanFacade,
            ImTaskCommandFacade taskCommandFacade,
            LarkMessageReplyTool replyTool,
            LarkIMMessageStreamService streamService,
            LarkOutboundMessageRetryService retryService,
            LarkIMTaskReplyFormatter replyFormatter,
            DocRefExtractionService docRefExtractionService,
            @Qualifier("plannerTaskExecutor") Executor plannerDispatchExecutor
    ) {
        this.plannerPlanFacade = plannerPlanFacade;
        this.taskCommandFacade = taskCommandFacade;
        this.replyTool = replyTool;
        this.streamService = streamService;
        this.retryService = retryService;
        this.replyFormatter = replyFormatter == null ? new LarkIMTaskReplyFormatter() : replyFormatter;
        this.docRefExtractionService = docRefExtractionService;
        this.plannerDispatchExecutor = plannerDispatchExecutor == null ? Runnable::run : plannerDispatchExecutor;
    }

    @Override
    public PlanTaskSession dispatch(LarkInboundMessage message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            log.warn("Scenario A inbound message ignored because content is empty: messageId={}, chatId={}",
                    message == null ? null : message.messageId(),
                    message == null ? null : message.chatId());
            return null;
        }

        PlanTaskSession accepted = acceptedSession(message);
        replyImmediateReceipt(message, accepted);
        try {
            plannerDispatchExecutor.execute(() -> dispatchAndReply(message));
        } catch (RejectedExecutionException exception) {
            log.warn("Scenario A planner dispatch queue rejected inbound message: messageId={}, chatId={}",
                    message.messageId(), message.chatId(), exception);
            PlanTaskSession failed = failedSession(message, "Planner queue is full");
            replyBySessionState(message, failed);
            return failed;
        }
        return accepted;
    }

    private void replyImmediateReceipt(LarkInboundMessage message, PlanTaskSession accepted) {
        String receipt = immediateReceiptText(message);
        if (!hasText(receipt)) {
            return;
        }
        replyText(message, accepted, receipt, "immediate receipt");
    }

    private String immediateReceiptText(LarkInboundMessage message) {
        if (plannerPlanFacade == null || message == null || !hasText(message.content())) {
            return "";
        }
        try {
            return plannerPlanFacade.previewImmediateReply(
                    message.content(),
                    buildWorkspaceContext(message),
                    null,
                    null
            );
        } catch (RuntimeException exception) {
            log.debug("Scenario A immediate receipt preview failed: messageId={}, chatId={}",
                    message.messageId(), message.chatId(), exception);
            return "";
        }
    }

    private void dispatchAndReply(LarkInboundMessage message) {
        PlanTaskSession session;
        try {
            session = plannerPlanFacade.plan(
                    message.content(),
                    buildWorkspaceContext(message),
                    null,
                    null
            );
        } catch (RuntimeException exception) {
            log.warn("Scenario A planner dispatch failed: messageId={}, chatId={}",
                    message.messageId(), message.chatId(), exception);
            session = failedSession(message, humanizeFailure(exception));
        }
        replyBySessionState(message, session);
        log.info("Scenario A inbound Lark message bridged to planner: messageId={}, chatId={}, taskId={}, phase={}",
                message.messageId(), message.chatId(), session == null ? null : session.getTaskId(),
                session == null ? null : session.getPlanningPhase());
    }

    private void replyBySessionState(LarkInboundMessage message, PlanTaskSession session) {
        if (message == null || session == null) {
            return;
        }
        TaskIntakeTypeEnum intakeType = session.getIntakeState() == null ? null : session.getIntakeState().getIntakeType();
        if (intakeType == TaskIntakeTypeEnum.CONFIRM_ACTION) {
            replyConfirmExecution(message, session);
            return;
        }
        if (intakeType == TaskIntakeTypeEnum.STATUS_QUERY) {
            replyStatus(message, session);
            return;
        }
        if (intakeType == TaskIntakeTypeEnum.UNKNOWN) {
            replyText(message, session, replyFormatter.uncertainIntent(session), "unknown intent");
            return;
        }
        if (intakeType == TaskIntakeTypeEnum.PLAN_ADJUSTMENT
                && session.getPlanningPhase() == PlanningPhaseEnum.COMPLETED
                && hasAssistantReply(session)) {
            String detail = session.getIntakeState().getAssistantReply();
            String confirmation = requestsResumeExecution(message == null ? null : message.content())
                    ? replyFormatter.completedArtifactEditClarification(detail)
                    : replyFormatter.completedArtifactEditApplied(detail);
            replyText(message, session, confirmation + "\n\n" + replyFormatter.status(snapshot(session)),
                    "plan adjustment completed");
            return;
        }
        if (intakeType == TaskIntakeTypeEnum.PLAN_ADJUSTMENT
                && session.getPlanningPhase() == PlanningPhaseEnum.EXECUTING) {
            String confirmation = replyFormatter.executionReplannedAndRestarted(
                    session.getIntakeState() == null ? null : session.getIntakeState().getAssistantReply());
            replyText(message, session, withStatus(confirmation, snapshot(session)), "plan adjustment resumed execution");
            return;
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.FAILED) {
            replyText(message, session, replyFormatter.failure(session, snapshot(session)), "failed");
            return;
        }
        if (intakeType == TaskIntakeTypeEnum.PLAN_ADJUSTMENT && hasAssistantReply(session)) {
            replyText(message, session, replyFormatter.assistantReply(session.getIntakeState().getAssistantReply()),
                    "plan adjustment clarification");
            return;
        }
        if (intakeType == TaskIntakeTypeEnum.PLAN_ADJUSTMENT && session.getPlanningPhase() == PlanningPhaseEnum.PLAN_READY) {
            replyText(message, session, replyFormatter.planAdjusted(session), "plan adjusted");
            return;
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.ASK_USER) {
            replyText(message, session, replyFormatter.clarification(session), "clarification");
            return;
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.PLAN_READY) {
            replyText(message, session, replyFormatter.planReady(session), "plan ready");
            return;
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.ABORTED) {
            replyText(message, session, replyFormatter.taskCancelled(), "cancelled");
            return;
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.COMPLETED) {
            replyText(message, session, replyFormatter.status(snapshot(session)), "terminal status");
        }
    }

    private void replyConfirmExecution(LarkInboundMessage message, PlanTaskSession session) {
        if (session.getPlanningPhase() == PlanningPhaseEnum.FAILED) {
            replyRetryExecution(message, session);
            return;
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.EXECUTING) {
            replyText(message, session, withStatus(replyFormatter.executionStarted(snapshot(session)), snapshot(session)),
                    "execution started");
            return;
        }
        if (taskCommandFacade == null || session.getPlanningPhase() != PlanningPhaseEnum.PLAN_READY) {
            replyText(message, session, replyFormatter.status(snapshot(session)), "confirm unavailable");
            return;
        }
        PlanTaskSession executing = taskCommandFacade.confirmExecution(session.getTaskId());
        if (executing != null && executing.getPlanningPhase() == PlanningPhaseEnum.FAILED) {
            replyText(message, executing, replyFormatter.status(snapshot(executing)), "execution failed");
            return;
        }
        replyText(message, executing, withStatus(replyFormatter.executionStarted(snapshot(executing)), snapshot(executing)),
                "execution started");
    }

    private void replyRetryExecution(LarkInboundMessage message, PlanTaskSession session) {
        if (taskCommandFacade == null) {
            replyText(message, session, replyFormatter.retryUnavailable(snapshot(session)), "retry unavailable");
            return;
        }
        PlanTaskSession retrying = taskCommandFacade.retryExecution(session.getTaskId(), message.content());
        if (retrying == null || retrying.getPlanningPhase() != PlanningPhaseEnum.EXECUTING) {
            replyText(message, session, replyFormatter.retryUnavailable(snapshot(session)), "retry unavailable");
            return;
        }
        replyText(message, retrying, withStatus(replyFormatter.retryStarted(snapshot(retrying)), snapshot(retrying)),
                "retry started");
    }

    private String withStatus(String confirmation, TaskRuntimeSnapshot snapshot) {
        String status = replyFormatter.status(snapshot);
        if (!hasText(status)) {
            return confirmation;
        }
        return confirmation + "\n" + status;
    }

    private void replyStatus(LarkInboundMessage message, PlanTaskSession session) {
        if (session.getIntakeState() != null && hasText(session.getIntakeState().getAssistantReply())) {
            replyText(message, session, replyFormatter.assistantReply(session.getIntakeState().getAssistantReply()),
                    "read-only reply");
            return;
        }
        replyText(message, session, replyFormatter.status(snapshot(session)), "status");
    }

    private TaskRuntimeSnapshot snapshot(PlanTaskSession session) {
        if (taskCommandFacade == null || session == null || !hasText(session.getTaskId())) {
            return null;
        }
        return taskCommandFacade.getRuntimeSnapshot(session.getTaskId());
    }

    private WorkspaceContext buildWorkspaceContext(LarkInboundMessage message) {
        List<String> docRefs = docRefExtractionService == null
                ? List.of()
                : docRefExtractionService.extractDocRefs(message.content(), message.rawContent());
        return WorkspaceContext.builder()
                .selectionType(docRefs.isEmpty() ? null : "DOCUMENT")
                .timeRange(null)
                .chatId(message.chatId())
                .threadId(message.threadId())
                .messageId(message.messageId())
                .senderOpenId(message.senderOpenId())
                .chatType(message.chatType())
                .inputSource(message.inputSource() == null ? null : message.inputSource().name())
                .selectedMessages(java.util.List.of())
                .docRefs(docRefs)
                .build();
    }

    private PlanTaskSession acceptedSession(LarkInboundMessage message) {
        return PlanTaskSession.builder()
                .taskId("im-pending-" + UUID.nameUUIDFromBytes(
                        safe(message == null ? null : message.messageId()).getBytes(StandardCharsets.UTF_8)))
                .rawInstruction(message == null ? null : message.content())
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build();
    }

    private PlanTaskSession failedSession(LarkInboundMessage message, String reason) {
        return PlanTaskSession.builder()
                .taskId("im-failed-" + UUID.nameUUIDFromBytes(
                        safe(message == null ? null : message.messageId()).getBytes(StandardCharsets.UTF_8)))
                .rawInstruction(message == null ? null : message.content())
                .planningPhase(PlanningPhaseEnum.FAILED)
                .transitionReason(reason)
                .build();
    }

    private String humanizeFailure(RuntimeException exception) {
        String message = exception == null ? null : exception.getMessage();
        if (!hasText(message)) {
            return "处理消息时遇到异常";
        }
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }

    private LarkMessageEvent toSourceEvent(LarkInboundMessage message) {
        return new LarkMessageEvent(
                message.eventId(),
                message.messageId(),
                message.chatId(),
                message.threadId(),
                message.chatType(),
                message.messageType(),
                message.content(),
                message.rawContent(),
                message.senderOpenId(),
                message.createTime(),
                false
        );
    }

    private void replyText(LarkInboundMessage message, PlanTaskSession session, String text, String replyType) {
        if (!hasText(text)) {
            return;
        }
        safeReplyText(message, session, text, replyType);
        safePublishText(message, session, text, replyType);
    }

    private void safeReplyText(LarkInboundMessage message, PlanTaskSession session, String text, String replyType) {
        if (replyTool == null) {
            return;
        }
        String idempotencyKey = buildIdempotencyKey(session, message, replyType);
        try {
            if (isP2P(message) && hasText(message.senderOpenId())) {
                replyTool.sendPrivateText(message.senderOpenId(), text, idempotencyKey);
            } else if (hasText(message.messageId())) {
                replyTool.replyText(message.messageId(), text, idempotencyKey);
            }
        } catch (RuntimeException exception) {
            log.warn("Scenario A {} reply failed but task state is kept: messageId={}, chatId={}, taskId={}",
                    replyType, message.messageId(), message.chatId(), session.getTaskId(), exception);
            enqueueReplyRetry(message, text, idempotencyKey);
        }
    }

    private void safePublishText(LarkInboundMessage message, PlanTaskSession session, String text, String publishType) {
        if (streamService == null) {
            return;
        }
        try {
            streamService.publishBotReply(toSourceEvent(message), text);
        } catch (RuntimeException exception) {
            log.warn("Scenario A {} stream publish failed: messageId={}, chatId={}, taskId={}",
                    publishType, message.messageId(), message.chatId(), session.getTaskId(), exception);
        }
    }

    private boolean isP2P(LarkInboundMessage message) {
        return message != null && "p2p".equalsIgnoreCase(message.chatType());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasAssistantReply(PlanTaskSession session) {
        return session != null
                && session.getIntakeState() != null
                && hasText(session.getIntakeState().getAssistantReply());
    }

    private void enqueueReplyRetry(LarkInboundMessage message, String text, String idempotencyKey) {
        if (retryService == null) {
            return;
        }
        if (isP2P(message) && hasText(message.senderOpenId())) {
            retryService.enqueuePrivateText(message.senderOpenId(), text, idempotencyKey);
            return;
        }
        if (hasText(message.messageId())) {
            retryService.enqueueReplyText(message.messageId(), text, idempotencyKey);
        }
    }

    private String buildIdempotencyKey(PlanTaskSession session, LarkInboundMessage message, String replyType) {
        String seed = replyType
                + "::" + (session == null ? "no-task" : safe(session.getTaskId()))
                + "::" + (message == null ? "no-message" : safe(message.messageId()))
                + "::" + (message == null ? "no-thread" : safe(message.threadId()))
                + "::" + (message == null ? "no-chat" : safe(message.chatId()));
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean requestsResumeExecution(String text) {
        if (!hasText(text)) {
            return false;
        }
        return text.contains("继续跑")
                || text.contains("继续执行")
                || text.contains("重新开始执行");
    }
}
