package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.enums.InputSourceEnum;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import com.lark.imcollab.gateway.im.event.LarkEventSubscriptionStatus;
import com.lark.imcollab.gateway.im.event.LarkMessageEvent;
import com.lark.imcollab.gateway.im.event.LarkMessageEventSubscriptionService;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LarkIMListenerService {

    private static final Logger log = LoggerFactory.getLogger(LarkIMListenerService.class);
    private static final String RECEIPT_TEXT = "任务已收到，正在处理";
    private static final String CONSUMER_ID = LarkIMListenerService.class.getName();

    private final LarkMessageEventSubscriptionService subscriptionService;
    private final LarkMessageReplyTool replyTool;
    private final LarkInboundMessageDispatcher dispatcher;
    private final LarkIMMessageStreamService streamService;

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

    public LarkIMListenerStatusResponse start(LarkIMListenerStartRequest request) {
        String profileName = requireValue(request.profileName(), "profileName");
        return startWithProfile(profileName);
    }

    public LarkIMListenerStatusResponse startDefault(String profileName) {
        return startWithProfile(normalizeOptionalProfileName(profileName));
    }

    private LarkIMListenerStatusResponse startWithProfile(String profileName) {
        LarkEventSubscriptionStatus status = subscriptionService.startMessageSubscription(
                profileName,
                CONSUMER_ID,
                event -> handleMessage(profileName, event)
        );
        return mapStatus(status);
    }

    public LarkIMListenerStatusResponse stop(LarkIMListenerStartRequest request) {
        String profileName = requireValue(request.profileName(), "profileName");
        return mapStatus(subscriptionService.stopMessageSubscription(profileName));
    }

    public LarkIMListenerStatusResponse status(String profileName) {
        return mapStatus(subscriptionService.getMessageSubscriptionStatus(requireValue(profileName, "profileName")));
    }

    private void handleMessage(String profileName, LarkMessageEvent event) {
        if (!shouldTriggerAgent(event)) {
            return;
        }
        try {
            dispatcher.dispatch(mapInboundMessage(event));
            replyTool.replyText(profileName, event.messageId(), RECEIPT_TEXT);
            publishReceiptReply(event);
        } catch (RuntimeException exception) {
            log.warn("Failed to handle inbound Lark message: messageId={}, profileName={}",
                    event.messageId(), profileName, exception);
        }
    }

    private void publishReceiptReply(LarkMessageEvent sourceEvent) {
        if (streamService != null) {
            streamService.publishBotReply(sourceEvent, RECEIPT_TEXT);
        }
    }

    private boolean shouldTriggerAgent(LarkMessageEvent event) {
        return "p2p".equalsIgnoreCase(event.chatType()) || event.mentionDetected();
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

    private LarkIMListenerStatusResponse mapStatus(LarkEventSubscriptionStatus status) {
        return new LarkIMListenerStatusResponse(
                status.profileName(),
                status.running(),
                status.state(),
                status.startedAt(),
                status.lastError()
        );
    }

    private String requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
        return value.trim();
    }

    private String normalizeOptionalProfileName(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return null;
        }
        return profileName.trim();
    }
}
