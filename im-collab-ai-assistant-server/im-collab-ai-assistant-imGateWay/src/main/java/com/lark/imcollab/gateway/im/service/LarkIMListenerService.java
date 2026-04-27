package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.enums.InputSourceEnum;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import com.lark.imcollab.skills.lark.event.LarkEventSubscriptionStatus;
import com.lark.imcollab.skills.lark.event.LarkMessageEvent;
import com.lark.imcollab.skills.lark.event.LarkMessageEventSubscriptionTool;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LarkIMListenerService {

    private static final Logger log = LoggerFactory.getLogger(LarkIMListenerService.class);
    private static final String RECEIPT_TEXT = "任务已收到，正在处理";

    private final LarkMessageEventSubscriptionTool subscriptionTool;
    private final LarkMessageReplyTool replyTool;
    private final LarkInboundMessageDispatcher dispatcher;

    public LarkIMListenerService(
            LarkMessageEventSubscriptionTool subscriptionTool,
            LarkMessageReplyTool replyTool,
            LarkInboundMessageDispatcher dispatcher
    ) {
        this.subscriptionTool = subscriptionTool;
        this.replyTool = replyTool;
        this.dispatcher = dispatcher;
    }

    public LarkIMListenerStatusResponse start(LarkIMListenerStartRequest request) {
        String profileName = requireValue(request.profileName(), "profileName");
        return startWithProfile(profileName);
    }

    public LarkIMListenerStatusResponse startDefault(String profileName) {
        return startWithProfile(normalizeOptionalProfileName(profileName));
    }

    private LarkIMListenerStatusResponse startWithProfile(String profileName) {
        LarkEventSubscriptionStatus status = subscriptionTool.startMessageSubscription(
                profileName,
                event -> handleMessage(profileName, event)
        );
        return mapStatus(status);
    }

    public LarkIMListenerStatusResponse stop(LarkIMListenerStartRequest request) {
        String profileName = requireValue(request.profileName(), "profileName");
        return mapStatus(subscriptionTool.stopMessageSubscription(profileName));
    }

    public LarkIMListenerStatusResponse status(String profileName) {
        return mapStatus(subscriptionTool.getMessageSubscriptionStatus(requireValue(profileName, "profileName")));
    }

    private void handleMessage(String profileName, LarkMessageEvent event) {
        try {
            dispatcher.dispatch(mapInboundMessage(event));
            replyTool.replyText(profileName, event.messageId(), RECEIPT_TEXT);
        } catch (RuntimeException exception) {
            log.warn("Failed to handle inbound Lark message: messageId={}, profileName={}",
                    event.messageId(), profileName, exception);
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
