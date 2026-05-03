package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.enums.InputSourceEnum;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import com.lark.imcollab.gateway.im.event.LarkEventSubscriptionStatus;
import com.lark.imcollab.gateway.im.event.LarkMessageEvent;
import com.lark.imcollab.gateway.im.event.LarkMessageEventSubscriptionService;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LarkIMListenerService {

    private static final Logger log = LoggerFactory.getLogger(LarkIMListenerService.class);
    private static final String CONSUMER_ID = LarkIMListenerService.class.getName();
    private static final long GROUP_MENTION_MIRROR_SUPPRESSION_WINDOW_MILLIS = 15_000L;
    private static final long INBOUND_DEDUP_WINDOW_MILLIS = 2L * 60L * 1000L;

    private final LarkMessageEventSubscriptionService subscriptionService;
    private final LarkInboundMessageDispatcher dispatcher;
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
        this.dispatcher = dispatcher;
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
        try {
            dispatcher.dispatch(mapInboundMessage(event));
        } catch (RuntimeException exception) {
            releaseInboundDedupKey(inboundDedupKey);
            log.warn("Failed to dispatch inbound Lark message to planner: messageId={}", event.messageId(), exception);
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
                event.rawContent(),
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

    private LarkIMListenerStatusResponse mapStatus(LarkEventSubscriptionStatus status) {
        return new LarkIMListenerStatusResponse(
                status.running(),
                status.state(),
                status.startedAt(),
                status.lastError()
        );
    }
}
