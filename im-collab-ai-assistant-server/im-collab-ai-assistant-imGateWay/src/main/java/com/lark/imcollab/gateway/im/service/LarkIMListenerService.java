package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.InputSourceEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import com.lark.imcollab.gateway.im.event.LarkEventSubscriptionStatus;
import com.lark.imcollab.gateway.im.event.LarkMessageEvent;
import com.lark.imcollab.gateway.im.event.LarkMessageEventSubscriptionService;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LarkIMListenerService {

    private static final Logger log = LoggerFactory.getLogger(LarkIMListenerService.class);
    private static final String RECEIPT_TEXT =
            "\u4efb\u52a1\u5df2\u6536\u5230\uff0c\u6b63\u5728\u5904\u7406\u3002\n\u8bf7\u7a0d\u7b49\uff0c\u6211\u4f1a\u5148\u5206\u6790\u5e76\u7ee7\u7eed\u56de\u590d\u4f60\u3002";
    private static final String CONSUMER_ID = LarkIMListenerService.class.getName();
    private static final long GROUP_MENTION_MIRROR_SUPPRESSION_WINDOW_MILLIS = 15_000L;
    private static final long INBOUND_DEDUP_WINDOW_MILLIS = 2L * 60L * 1000L;

    private final LarkMessageEventSubscriptionService subscriptionService;
    private final LarkMessageReplyTool replyTool;
    private final LarkInboundMessageDispatcher dispatcher;
    private final LarkIMMessageStreamService streamService;
    private final LarkOutboundMessageRetryService retryService;
    private final LarkIMListenerProperties properties;
    private final Map<String, Long> recentGroupMentions = new ConcurrentHashMap<>();
    private final Map<String, Long> processedInboundMessages = new ConcurrentHashMap<>();
    private volatile long listenerStartedAtEpochMillis;

    public LarkIMListenerService(
            LarkMessageEventSubscriptionService subscriptionService,
            LarkMessageReplyTool replyTool,
            LarkInboundMessageDispatcher dispatcher
    ) {
        this(subscriptionService, replyTool, dispatcher, null, null, null);
    }

    public LarkIMListenerService(
            LarkMessageEventSubscriptionService subscriptionService,
            LarkMessageReplyTool replyTool,
            LarkInboundMessageDispatcher dispatcher,
            LarkIMMessageStreamService streamService,
            LarkOutboundMessageRetryService retryService
    ) {
        this(subscriptionService, replyTool, dispatcher, streamService, retryService, null);
    }

    @Autowired
    public LarkIMListenerService(
            LarkMessageEventSubscriptionService subscriptionService,
            LarkMessageReplyTool replyTool,
            LarkInboundMessageDispatcher dispatcher,
            LarkIMMessageStreamService streamService,
            LarkOutboundMessageRetryService retryService,
            LarkIMListenerProperties properties
    ) {
        this.subscriptionService = subscriptionService;
        this.replyTool = replyTool;
        this.dispatcher = dispatcher;
        this.streamService = streamService;
        this.retryService = retryService;
        this.properties = properties;
    }

    public LarkIMListenerStatusResponse start() {
        listenerStartedAtEpochMillis = System.currentTimeMillis();
        LarkEventSubscriptionStatus status = subscriptionService.startMessageSubscription(
                CONSUMER_ID,
                this::handleMessage
        );
        return mapStatus(status);
    }

    public LarkIMListenerStatusResponse stop() {
        return mapStatus(subscriptionService.stopMessageSubscription());
    }

    public LarkIMListenerStatusResponse status() {
        return mapStatus(subscriptionService.getMessageSubscriptionStatus());
    }

    private void handleMessage(LarkMessageEvent event) {
        if (shouldIgnoreBotSender(event)) {
            return;
        }
        if (!shouldTriggerAgent(event)) {
            return;
        }
        if (shouldIgnoreStartupReplay(event)) {
            return;
        }
        if (shouldIgnoreDuplicateInbound(event)) {
            return;
        }
        if (shouldSuppressMirroredP2PEvent(event)) {
            return;
        }

        String inboundDedupKey = buildInboundDedupKey(event);
        PlanTaskSession session;
        try {
            session = dispatcher.dispatch(mapInboundMessage(event));
        } catch (RuntimeException exception) {
            releaseInboundDedupKey(inboundDedupKey);
            log.warn("Failed to dispatch inbound Lark message to planner: messageId={}", event.messageId(), exception);
            return;
        }
        if (!shouldSendReceipt(session)) {
            return;
        }
        safeSendReceiptReply(event);
        safePublishReceiptReply(event);
    }

    private void sendReceiptReply(LarkMessageEvent event) {
        String idempotencyKey = buildReceiptIdempotencyKey(event);
        if (isP2P(event) && hasText(event.senderOpenId())) {
            replyTool.sendPrivateText(event.senderOpenId(), RECEIPT_TEXT, idempotencyKey);
            return;
        }
        replyTool.replyText(event.messageId(), RECEIPT_TEXT, idempotencyKey);
    }

    private void publishReceiptReply(LarkMessageEvent sourceEvent) {
        if (streamService != null) {
            streamService.publishBotReply(sourceEvent, RECEIPT_TEXT);
        }
    }

    private void safeSendReceiptReply(LarkMessageEvent event) {
        try {
            sendReceiptReply(event);
        } catch (RuntimeException exception) {
            log.warn("Failed to send Lark receipt reply: messageId={}, chatId={}",
                    event.messageId(), event.chatId(), exception);
            enqueueReceiptRetry(event);
        }
    }

    private void safePublishReceiptReply(LarkMessageEvent sourceEvent) {
        try {
            publishReceiptReply(sourceEvent);
        } catch (RuntimeException exception) {
            log.warn("Failed to publish receipt reply to frontend stream: messageId={}, chatId={}",
                    sourceEvent.messageId(), sourceEvent.chatId(), exception);
        }
    }

    private boolean shouldTriggerAgent(LarkMessageEvent event) {
        return isP2P(event) || event.mentionDetected();
    }

    private boolean shouldIgnoreBotSender(LarkMessageEvent event) {
        if (event == null) {
            return true;
        }
        String senderType = event.senderType();
        if ("bot".equalsIgnoreCase(senderType) || "app".equalsIgnoreCase(senderType)) {
            log.info("Ignoring bot-authored Lark message: eventId={}, messageId={}, senderType={}",
                    event.eventId(), event.messageId(), senderType);
            return true;
        }
        if ("bot".equalsIgnoreCase(event.senderOpenId())) {
            log.info("Ignoring local bot Lark message projection: eventId={}, messageId={}",
                    event.eventId(), event.messageId());
            return true;
        }
        return false;
    }

    private boolean shouldIgnoreDuplicateInbound(LarkMessageEvent event) {
        cleanupExpiredInboundMessages();
        String dedupKey = buildInboundDedupKey(event);
        if (dedupKey == null) {
            return false;
        }
        Long existingTimestamp = processedInboundMessages.putIfAbsent(dedupKey, System.currentTimeMillis());
        if (existingTimestamp == null) {
            return false;
        }
        if (System.currentTimeMillis() - existingTimestamp > INBOUND_DEDUP_WINDOW_MILLIS) {
            processedInboundMessages.put(dedupKey, System.currentTimeMillis());
            return false;
        }
        log.info("Ignoring duplicate inbound Lark message: eventId={}, messageId={}, chatId={}",
                event.eventId(), event.messageId(), event.chatId());
        return true;
    }

    private boolean shouldIgnoreStartupReplay(LarkMessageEvent event) {
        if (event == null || properties == null || !properties.isSuppressStartupReplayEnabled()) {
            return false;
        }
        long startedAt = listenerStartedAtEpochMillis;
        if (startedAt <= 0L) {
            return false;
        }
        Long eventTime = parseEpochMillis(event.createTime());
        if (eventTime == null) {
            return false;
        }
        long graceMillis = Math.max(0L, properties.getStartupReplayGracePeriodMillis());
        if (eventTime >= startedAt - graceMillis) {
            return false;
        }
        log.info("Ignoring stale Lark message replayed on listener startup: eventId={}, messageId={}, chatId={}, createTime={}, listenerStartedAt={}",
                event.eventId(), event.messageId(), event.chatId(), event.createTime(), startedAt);
        return true;
    }

    private boolean shouldSuppressMirroredP2PEvent(LarkMessageEvent event) {
        cleanupExpiredGroupMentions();
        String dedupKey = buildMirrorSuppressionKey(event);
        if (dedupKey == null) {
            return false;
        }
        if (!isP2P(event) && event.mentionDetected()) {
            recentGroupMentions.put(dedupKey, System.currentTimeMillis());
            return false;
        }
        if (!isP2P(event)) {
            return false;
        }
        Long recentTimestamp = recentGroupMentions.get(dedupKey);
        if (recentTimestamp == null) {
            return false;
        }
        if (System.currentTimeMillis() - recentTimestamp > GROUP_MENTION_MIRROR_SUPPRESSION_WINDOW_MILLIS) {
            recentGroupMentions.remove(dedupKey, recentTimestamp);
            return false;
        }
        log.info("Ignoring mirrored p2p inbound Lark message after group mention: messageId={}, senderOpenId={}, content={}",
                event.messageId(), event.senderOpenId(), event.content());
        return true;
    }

    private String buildInboundDedupKey(LarkMessageEvent event) {
        if (event == null) {
            return null;
        }
        if (hasText(event.eventId())) {
            return "event:" + event.eventId().trim();
        }
        if (hasText(event.messageId())) {
            return "message:" + event.messageId().trim();
        }
        return null;
    }

    private String buildMirrorSuppressionKey(LarkMessageEvent event) {
        if (event == null || !hasText(event.senderOpenId()) || !hasText(event.content())) {
            return null;
        }
        return event.senderOpenId().trim() + "::" + event.content().trim();
    }

    private void cleanupExpiredGroupMentions() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = recentGroupMentions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() > GROUP_MENTION_MIRROR_SUPPRESSION_WINDOW_MILLIS) {
                iterator.remove();
            }
        }
    }

    private void cleanupExpiredInboundMessages() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = processedInboundMessages.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() > INBOUND_DEDUP_WINDOW_MILLIS) {
                iterator.remove();
            }
        }
    }

    private void releaseInboundDedupKey(String dedupKey) {
        if (dedupKey == null) {
            return;
        }
        processedInboundMessages.remove(dedupKey);
    }

    private LarkInboundMessage mapInboundMessage(LarkMessageEvent event) {
        return new LarkInboundMessage(
                event.eventId(),
                event.messageId(),
                event.chatId(),
                event.threadId(),
                event.chatType(),
                event.messageType(),
                event.content(),
                event.senderOpenId(),
                event.createTime(),
                mapInputSource(event.chatType())
        );
    }

    private InputSourceEnum mapInputSource(String chatType) {
        if ("p2p".equalsIgnoreCase(chatType)) {
            return InputSourceEnum.LARK_PRIVATE_CHAT;
        }
        return InputSourceEnum.LARK_GROUP;
    }

    private boolean isP2P(LarkMessageEvent event) {
        return event != null && "p2p".equalsIgnoreCase(event.chatType());
    }

    private boolean shouldSendReceipt(PlanTaskSession session) {
        if (session != null
                && session.getIntakeState() != null
                && hasText(session.getIntakeState().getAssistantReply())) {
            return false;
        }
        return session == null
                || (session.getPlanningPhase() != PlanningPhaseEnum.ASK_USER
                && session.getPlanningPhase() != PlanningPhaseEnum.PLAN_READY
                && session.getPlanningPhase() != PlanningPhaseEnum.EXECUTING
                && session.getPlanningPhase() != PlanningPhaseEnum.COMPLETED
                && session.getPlanningPhase() != PlanningPhaseEnum.FAILED
                && session.getPlanningPhase() != PlanningPhaseEnum.ABORTED);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Long parseEpochMillis(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void enqueueReceiptRetry(LarkMessageEvent event) {
        if (retryService == null || event == null) {
            return;
        }
        String idempotencyKey = buildReceiptIdempotencyKey(event);
        if (isP2P(event) && hasText(event.senderOpenId())) {
            retryService.enqueuePrivateText(event.senderOpenId(), RECEIPT_TEXT, idempotencyKey);
            return;
        }
        if (hasText(event.messageId())) {
            retryService.enqueueReplyText(event.messageId(), RECEIPT_TEXT, idempotencyKey);
        }
    }

    private String buildReceiptIdempotencyKey(LarkMessageEvent event) {
        String seed = "receipt::"
                + safe(event == null ? null : event.messageId())
                + "::" + safe(event == null ? null : event.chatId())
                + "::" + safe(event == null ? null : event.threadId());
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private LarkIMListenerStatusResponse mapStatus(LarkEventSubscriptionStatus status) {
        return new LarkIMListenerStatusResponse(
                status.running(),
                status.state(),
                status.startedAt(),
                status.lastError()
        );
    }
}
