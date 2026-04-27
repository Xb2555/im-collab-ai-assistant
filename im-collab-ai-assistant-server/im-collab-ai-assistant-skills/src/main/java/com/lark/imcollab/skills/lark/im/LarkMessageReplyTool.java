package com.lark.imcollab.skills.lark.im;

import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LarkMessageReplyTool {

    private final LarkCliClient larkCliClient;

    public LarkMessageReplyTool(LarkCliClient larkCliClient) {
        this.larkCliClient = larkCliClient;
    }

    @Tool(description = "Scenario A: reply to a Lark message as bot under the selected profile.")
    public void replyText(String profileName, String messageId, String text) {
        String normalizedMessageId = requireValue(messageId, "messageId");
        String normalizedText = requireValue(text, "text");

        List<String> args;
        if (profileName == null || profileName.isBlank()) {
            args = List.of(
                    "im", "+messages-reply",
                    "--message-id", normalizedMessageId,
                    "--text", normalizedText,
                    "--as", "bot"
            );
        } else {
            args = List.of(
                    "--profile", profileName.trim(),
                    "im", "+messages-reply",
                    "--message-id", normalizedMessageId,
                    "--text", normalizedText,
                    "--as", "bot"
            );
        }
        CliCommandResult result = larkCliClient.execute(args);
        if (!result.isSuccess()) {
            throw new IllegalStateException(larkCliClient.extractErrorMessage(result.output()));
        }
    }

    private String requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
        return value.trim();
    }
}
