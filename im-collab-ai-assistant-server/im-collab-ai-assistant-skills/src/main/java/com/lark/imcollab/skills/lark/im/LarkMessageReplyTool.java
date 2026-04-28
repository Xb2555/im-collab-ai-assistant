package com.lark.imcollab.skills.lark.im;

import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class LarkMessageReplyTool {

    private static final Logger log = LoggerFactory.getLogger(LarkMessageReplyTool.class);

    private final LarkBotMessageClient messageClient;
    private final LarkCliClient larkCliClient;

    @Autowired
    public LarkMessageReplyTool(LarkBotMessageClient messageClient) {
        this.messageClient = messageClient;
        this.larkCliClient = null;
    }

    LarkMessageReplyTool(LarkCliClient larkCliClient) {
        this.messageClient = null;
        this.larkCliClient = larkCliClient;
    }

    @Tool(description = "Scenario A: reply to a Lark group or thread message as bot by source messageId.")
    public void replyText(String messageId, String text) {
        String normalizedMessageId = requireValue(messageId, "messageId");
        String normalizedText = requireValue(text, "text");
        log.info("Sending Lark group reply: messageId={}, text={}", normalizedMessageId, escapeForLog(normalizedText));
        if (messageClient != null) {
            messageClient.replyText(normalizedMessageId, normalizedText);
            return;
        }
        fallbackToCliReply(normalizedMessageId, normalizedText);
    }

    @Tool(description = "Scenario A: send a text message to a Lark single-chat user as bot by user open_id.")
    public LarkBotMessageResult sendPrivateText(String openId, String text) {
        String normalizedOpenId = requireValue(openId, "openId");
        String normalizedText = requireValue(text, "text");
        log.info("Sending Lark p2p message: openId={}, text={}", normalizedOpenId, escapeForLog(normalizedText));
        return requireMessageClient().sendTextToOpenId(normalizedOpenId, normalizedText);
    }

    private void fallbackToCliReply(String messageId, String text) {
        var result = larkCliClient.execute(java.util.List.of(
                "im", "+messages-reply",
                "--message-id", messageId,
                "--text", text,
                "--as", "bot"
        ));
        if (!result.isSuccess()) {
            throw new IllegalStateException(larkCliClient.extractErrorMessage(result.output()));
        }
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
