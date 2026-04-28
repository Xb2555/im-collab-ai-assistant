package com.lark.imcollab.skills.lark.im;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class LarkMessageReplyTool {

    private static final Logger log = LoggerFactory.getLogger(LarkMessageReplyTool.class);

    private final LarkBotMessageClient messageClient;

    @Autowired
    public LarkMessageReplyTool(LarkBotMessageClient messageClient) {
        this.messageClient = messageClient;
    }

    @Tool(description = "Scenario A: reply to a Lark group or thread message as bot by source messageId.")
    public void replyText(String messageId, String text) {
        replyText(messageId, text, UUID.randomUUID().toString());
    }

    public void replyText(String messageId, String text, String idempotencyKey) {
        String normalizedMessageId = requireValue(messageId, "messageId");
        String normalizedText = requireValue(text, "text");
        String normalizedIdempotencyKey = requireValue(idempotencyKey, "idempotencyKey");
        log.info("Sending Lark group reply: messageId={}, text={}", normalizedMessageId, escapeForLog(normalizedText));
        requireMessageClient().replyText(normalizedMessageId, normalizedText, normalizedIdempotencyKey);
    }

    @Tool(description = "Scenario A: send a text message to a Lark single-chat user as bot by user open_id.")
    public LarkBotMessageResult sendPrivateText(String openId, String text) {
        return sendPrivateText(openId, text, UUID.randomUUID().toString());
    }

    public LarkBotMessageResult sendPrivateText(String openId, String text, String idempotencyKey) {
        String normalizedOpenId = requireValue(openId, "openId");
        String normalizedText = requireValue(text, "text");
        String normalizedIdempotencyKey = requireValue(idempotencyKey, "idempotencyKey");
        log.info("Sending Lark p2p message: openId={}, text={}", normalizedOpenId, escapeForLog(normalizedText));
        return requireMessageClient().sendTextToOpenId(normalizedOpenId, normalizedText, normalizedIdempotencyKey);
    }

    private LarkBotMessageClient requireMessageClient() {
        if (messageClient == null) {
            throw new IllegalStateException("Lark bot message client is not available");
        }
        return messageClient;
    }

    private String requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
        return value.trim();
    }

    private String escapeForLog(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }
}
