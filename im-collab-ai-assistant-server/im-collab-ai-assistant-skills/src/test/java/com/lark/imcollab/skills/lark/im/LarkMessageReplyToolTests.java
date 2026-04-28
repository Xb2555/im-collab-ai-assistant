package com.lark.imcollab.skills.lark.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliCommandExecutor;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LarkMessageReplyToolTests {

    @Test
    void shouldReplyToMessageAsBotWithDefaultCliConfiguration() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        LarkCliClient client = new LarkCliClient(executor, new LarkCliProperties(), new ObjectMapper());
        LarkMessageReplyTool tool = new LarkMessageReplyTool(client);

        tool.replyText("om_1", "任务已收到，正在处理");

        assertThat(executor.recordedCommands()).hasSize(1);
        assertThat(executor.recordedCommands().get(0).arguments())
                .containsExactly("im", "+messages-reply", "--message-id", "om_1", "--text",
                        "任务已收到，正在处理", "--as", "bot");
    }

    private static final class StubCliCommandExecutor implements CliCommandExecutor {

        private final List<CliCommand> recordedCommands = new ArrayList<>();

        @Override
        public CliCommandResult execute(CliCommand command) throws IOException {
            recordedCommands.add(command);
            return new CliCommandResult(0, "{}");
        }

        private List<CliCommand> recordedCommands() {
            return recordedCommands;
        }
    }
}
