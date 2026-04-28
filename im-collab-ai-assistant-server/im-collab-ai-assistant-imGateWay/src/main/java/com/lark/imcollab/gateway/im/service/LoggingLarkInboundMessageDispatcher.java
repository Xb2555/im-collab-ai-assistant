package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PromptSlotState;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import com.lark.imcollab.gateway.im.event.LarkMessageEvent;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class LoggingLarkInboundMessageDispatcher implements LarkInboundMessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingLarkInboundMessageDispatcher.class);
    private static final String WORKSPACE_SELECTION_TYPE = "MESSAGE";
    private static final String CLARIFICATION_PREFIX =
            "\u4e3a\u4e86\u7ee7\u7eed\u89c4\u5212\uff0c\u8bf7\u8865\u5145\u4ee5\u4e0b\u4fe1\u606f\uff1a";
    private static final String PLAN_READY_PREFIX =
            "\u89c4\u5212\u5df2\u751f\u6210\uff0c\u5efa\u8bae\u6309\u4ee5\u4e0b\u65b9\u5f0f\u63a8\u8fdb\uff1a";
    private static final String PLAN_EDIT_HINT =
            "\u5982\u9700\u4fee\u6539\u8ba1\u5212\uff0c\u53ef\u4ee5\u76f4\u63a5\u56de\u590d\u8865\u5145\u8981\u6c42\u3002";
    private static final String TASK_CANCELLED_TEXT =
            "\u4efb\u52a1\u5df2\u53d6\u6d88\uff0c\u540e\u7eed\u4e0d\u4f1a\u7ee7\u7eed\u89c4\u5212\u6216\u6267\u884c\u3002";

    private final PlannerPlanFacade plannerPlanFacade;
    private final LarkMessageReplyTool replyTool;
    private final LarkIMMessageStreamService streamService;
    private final LarkOutboundMessageRetryService retryService;

    public LoggingLarkInboundMessageDispatcher(PlannerPlanFacade plannerPlanFacade) {
        this(plannerPlanFacade, null, null, null);
    }

    @Autowired
    public LoggingLarkInboundMessageDispatcher(
            PlannerPlanFacade plannerPlanFacade,
            LarkMessageReplyTool replyTool,
            LarkIMMessageStreamService streamService,
            LarkOutboundMessageRetryService retryService
    ) {
        this.plannerPlanFacade = plannerPlanFacade;
        this.replyTool = replyTool;
        this.streamService = streamService;
        this.retryService = retryService;
    }

    @Override
    public PlanTaskSession dispatch(LarkInboundMessage message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            log.warn("Scenario A inbound message ignored because content is empty: messageId={}, chatId={}",
                    message == null ? null : message.messageId(),
                    message == null ? null : message.chatId());
            return null;
        }

        PlanTaskSession session = plannerPlanFacade.plan(
                message.content(),
                buildWorkspaceContext(message),
                null,
                null
        );
        replyClarificationIfNeeded(message, session);
        replyPlanReadyIfNeeded(message, session);
        replyCancelledIfNeeded(message, session);
        log.info("Scenario A inbound Lark message bridged to planner: messageId={}, chatId={}, taskId={}, phase={}",
                message.messageId(), message.chatId(), session.getTaskId(), session.getPlanningPhase());
        return session;
    }

    private void replyClarificationIfNeeded(LarkInboundMessage message, PlanTaskSession session) {
        if (message == null || session == null || session.getPlanningPhase() != PlanningPhaseEnum.ASK_USER) {
            return;
        }

        String clarificationText = buildClarificationText(session);
        if (!hasText(clarificationText)) {
            return;
        }

        safeReplyClarification(message, session, clarificationText);
        safePublishClarification(message, session, clarificationText);
    }

    private void replyPlanReadyIfNeeded(LarkInboundMessage message, PlanTaskSession session) {
        if (message == null || session == null || session.getPlanningPhase() != PlanningPhaseEnum.PLAN_READY) {
            return;
        }
        String planReadyText = buildPlanReadyText(session);
        if (!hasText(planReadyText)) {
            return;
        }
        safeReplyText(message, session, planReadyText, "plan ready");
        safePublishText(message, session, planReadyText, "plan ready");
    }

    private void replyCancelledIfNeeded(LarkInboundMessage message, PlanTaskSession session) {
        if (message == null || session == null || session.getPlanningPhase() != PlanningPhaseEnum.ABORTED) {
            return;
        }
        safeReplyText(message, session, TASK_CANCELLED_TEXT, "cancelled");
        safePublishText(message, session, TASK_CANCELLED_TEXT, "cancelled");
    }

    private String buildClarificationText(PlanTaskSession session) {
        List<String> prompts = new ArrayList<>();
        if (session.getActivePromptSlots() != null) {
            for (PromptSlotState slot : session.getActivePromptSlots()) {
                if (slot != null && !slot.isAnswered() && hasText(slot.getPrompt())) {
                    prompts.add(slot.getPrompt().trim());
                }
            }
        }
        if (prompts.isEmpty() && session.getClarificationQuestions() != null) {
            for (String question : session.getClarificationQuestions()) {
                if (hasText(question)) {
                    prompts.add(question.trim());
                }
            }
        }
        if (prompts.isEmpty()) {
            return null;
        }
        if (prompts.size() == 1) {
            return prompts.get(0);
        }
        return formatClarificationList(prompts);
    }

    private WorkspaceContext buildWorkspaceContext(LarkInboundMessage message) {
        return WorkspaceContext.builder()
                .selectionType(WORKSPACE_SELECTION_TYPE)
                .timeRange(message.createTime())
                .chatId(message.chatId())
                .threadId(message.threadId())
                .messageId(message.messageId())
                .senderOpenId(message.senderOpenId())
                .chatType(message.chatType())
                .inputSource(message.inputSource() == null ? null : message.inputSource().name())
                .selectedMessages(message.content() == null || message.content().isBlank()
                        ? java.util.List.of()
                        : java.util.List.of(message.content()))
                .build();
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
                message.senderOpenId(),
                message.createTime(),
                false
        );
    }

    private String formatClarificationList(List<String> prompts) {
        StringBuilder builder = new StringBuilder(CLARIFICATION_PREFIX);
        for (int index = 0; index < prompts.size(); index++) {
            builder.append("\n").append(index + 1).append(". ").append(prompts.get(index));
        }
        return builder.toString();
    }

    private String buildPlanReadyText(PlanTaskSession session) {
        StringBuilder builder = new StringBuilder(PLAN_READY_PREFIX);

        String taskBrief = firstNonBlank(
                session.getPlanBlueprint() == null ? null : session.getPlanBlueprint().getTaskBrief(),
                session.getPlanBlueprintSummary(),
                session.getIntentSnapshot() == null ? null : session.getIntentSnapshot().getUserGoal()
        );
        if (hasText(taskBrief)) {
            builder.append("\n").append("\u4efb\u52a1\uff1a").append(taskBrief.trim());
        }

        appendListSection(
                builder,
                "\u4ea4\u4ed8\u7269\uff1a",
                session.getPlanBlueprint() == null ? null : session.getPlanBlueprint().getDeliverables(),
                5
        );

        appendPlanCardsSection(builder, session.getPlanBlueprint());
        appendListSection(
                builder,
                "\u6210\u529f\u6807\u51c6\uff1a",
                session.getPlanBlueprint() == null ? null : session.getPlanBlueprint().getSuccessCriteria(),
                4
        );
        appendListSection(
                builder,
                "\u98ce\u9669\u5173\u6ce8\uff1a",
                session.getPlanBlueprint() == null ? null : session.getPlanBlueprint().getRisks(),
                4
        );

        builder.append("\n").append(PLAN_EDIT_HINT);
        return builder.toString();
    }

    private void appendPlanCardsSection(StringBuilder builder, PlanBlueprint blueprint) {
        if (blueprint == null || blueprint.getPlanCards() == null || blueprint.getPlanCards().isEmpty()) {
            return;
        }
        builder.append("\n").append("\u6267\u884c\u6b65\u9aa4\uff1a");
        int limit = Math.min(blueprint.getPlanCards().size(), 6);
        for (int index = 0; index < limit; index++) {
            UserPlanCard card = blueprint.getPlanCards().get(index);
            if (card == null || !hasText(card.getTitle())) {
                continue;
            }
            builder.append("\n").append(index + 1).append(". ");
            if (card.getType() != null) {
                builder.append("[").append(card.getType().name()).append("] ");
            }
            builder.append(card.getTitle().trim());
            if (hasText(card.getDescription())) {
                builder.append(" - ").append(card.getDescription().trim());
            }
        }
    }

    private void appendListSection(StringBuilder builder, String title, List<String> items, int maxItems) {
        if (items == null || items.isEmpty()) {
            return;
        }
        builder.append("\n").append(title);
        int added = 0;
        for (String item : items) {
            if (!hasText(item)) {
                continue;
            }
            builder.append("\n").append(added + 1).append(". ").append(item.trim());
            added++;
            if (added >= maxItems) {
                break;
            }
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private void safeReplyClarification(LarkInboundMessage message, PlanTaskSession session, String clarificationText) {
        safeReplyText(message, session, clarificationText, "clarification");
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

    private void safePublishClarification(LarkInboundMessage message, PlanTaskSession session, String clarificationText) {
        safePublishText(message, session, clarificationText, "clarification");
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
}
