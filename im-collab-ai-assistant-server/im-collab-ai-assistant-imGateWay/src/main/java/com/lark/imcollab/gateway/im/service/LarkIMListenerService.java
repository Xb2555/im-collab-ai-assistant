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
    private static final String RECEIPT_TEXT = """
            任务已收到，正在处理。
            请稍等，我会先分析并继续回复你。
            """;
    private static final String CONSUMER_ID = LarkIMListenerService.class.getName();
    private static final long GROUP_MENTION_MIRROR_SUPPRESSION_WINDOW_MILLIS = 15_000L;

    private final LarkMessageEventSubscriptionService subscriptionService;
    private final LarkMessageReplyTool replyTool;
    private final LarkInboundMessageDispatcher dispatcher;
    private final LarkIMMessageStreamService streamService;
    private final Map<String, Long> recentGroupMentions = new ConcurrentHashMap<>();

    public LarkIMListenerService(
            LarkMessageEventSubscriptionService subscriptionService,
            LarkMessageReplyTool replyTool,
            LarkInboundMessageDispatcher dispatcher
    ) {
        this(subscriptionService, replyTool, dispatcher, null);
    }

    @Autowired
    public LarkIMListenerService(
            LarkMessageEventSubscriptionService subscriptionService,
            LarkMessageReplyTool replyTool,
            LarkInboundMessageDispatcher dispatcher,
            LarkIMMessageStreamService streamService
    ) {
        this.subscriptionService = subscriptionService;
        this.replyTool = replyTool;
        this.dispatcher = dispatcher;
        this.streamService = streamService;
    }

    public LarkIMListenerStatusResponse start() {
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
        if (!shouldTriggerAgent(event)) {
            return;
        }
        if (shouldSuppressMirroredP2PEvent(event)) {
            return;
        }
        try {
            dispatcher.dispatch(mapInboundMessage(event));
            sendReceiptReply(event);
            publishReceiptReply(event);
        } catch (RuntimeException exception) {
            log.warn("Failed to handle inbound Lark message: messageId={}", event.messageId(), exception);
        }
    }

    private void sendReceiptReply(LarkMessageEvent event) {
        if (isP2P(event) && event.senderOpenId() != null && !event.senderOpenId().isBlank()) {
            replyTool.sendPrivateText(event.senderOpenId(), RECEIPT_TEXT);
            return;
        }
        replyTool.replyText(event.messageId(), RECEIPT_TEXT);
    }

    private void publishReceiptReply(LarkMessageEvent sourceEvent) {
        if (streamService != null) {
            streamService.publishBotReply(sourceEvent, RECEIPT_TEXT);
        }
    }

    private boolean shouldTriggerAgent(LarkMessageEvent event) {
        return isP2P(event) || event.mentionDetected();
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

    private String buildMirrorSuppressionKey(LarkMessageEvent event) {
        if (event == null || event.senderOpenId() == null || event.senderOpenId().isBlank()) {
            return null;
        }
        if (event.content() == null || event.content().isBlank()) {
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

    private LarkInboundMessage mapInboundMessage(LarkMessageEvent event) {
        return new LarkInboundMessage(
                event.eventId(),
                event.messageId(),
                event.chatId(),
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

    private LarkIMListenerStatusResponse mapStatus(LarkEventSubscriptionStatus status) {
        return new LarkIMListenerStatusResponse(
                status.running(),
                status.state(),
                status.startedAt(),
                status.lastError()
        );
    }
}
