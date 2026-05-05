package com.lark.imcollab.skills.lark.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliCommandExecutor;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LarkMessageSearchToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void searchesWithWindowsSafeArgumentListAndChineseQuery() {
        String originalOs = System.getProperty("os.name");
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(0, """
                    {"items":[{"message_id":"om_1","msg_type":"text","chat_id":"oc_1","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"body":{"content":"采购评审：供应商A报价最低"}}],"has_more":false}
                    """);
        };

        try {
            System.setProperty("os.name", "Windows 11");
            LarkCliProperties properties = new LarkCliProperties();
            properties.setExecutable("C:\\Program Files\\nodejs\\lark-cli.cmd");
            LarkMessageSearchTool tool = new LarkMessageSearchTool(
                    new LarkCliClient(executor, properties, objectMapper),
                    properties
            );

            LarkMessageSearchResult result = tool.searchMessages("采购评审", "oc_1", null, null, 50, 5);

            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).content()).contains("采购评审");
            assertThat(commands).hasSize(1);
            assertThat(commands.get(0).executable()).isEqualTo("C:\\Program Files\\nodejs\\lark-cli.cmd");
            assertThat(commands.get(0).arguments()).containsExactly(
                    "im",
                    "+messages-search",
                    "--as",
                    "user",
                    "--query",
                    "采购评审",
                    "--chat-id",
                    "oc_1",
                    "--page-size",
                    "50",
                    "--page-limit",
                    "5",
                    "--format",
                    "json"
            );
            assertThat(commands.get(0).stdin()).isNull();
        } finally {
            if (originalOs == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", originalOs);
            }
        }
    }

    @Test
    void missingSearchScopeReturnsActionableMessage() {
        CliCommandExecutor executor = command -> new CliCommandResult(3, """
                {"ok":false,"error":{"type":"missing_scope","message":"missing required scope(s): search:message"}}
                """);
        LarkCliProperties properties = new LarkCliProperties();
        LarkMessageSearchTool tool = new LarkMessageSearchTool(
                new LarkCliClient(executor, properties, objectMapper),
                properties
        );

        assertThatThrownBy(() -> tool.searchMessages("采购评审", "oc_1", null, null, 50, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lark-cli auth login --scope \"search:message\"");
    }

    @Test
    void timeWindowWithoutQueryUsesChatMessagesList() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(0, """
                    {"data":{"items":[{"message_id":"om_2","chat_id":"oc_1","sender":{"id":"ou_2","name":"吴纯瑶","sender_type":"user"},"body":{"content":"昨天下午讨论：先整理成文档。"}}],"has_more":false}}
                    """);
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkMessageSearchTool tool = new LarkMessageSearchTool(
                new LarkCliClient(executor, properties, objectMapper),
                properties
        );

        LarkMessageSearchResult result = tool.searchMessages("", "oc_1", "2026-05-04T12:00:00+08:00", "2026-05-04T18:00:00+08:00", 50, 1);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).content()).contains("昨天下午讨论");
        assertThat(commands).hasSize(1);
        assertThat(commands.get(0).arguments()).containsExactly(
                "im",
                "+chat-messages-list",
                "--as",
                "user",
                "--chat-id",
                "oc_1",
                "--start",
                "2026-05-04T12:00:00+08:00",
                "--end",
                "2026-05-04T18:00:00+08:00",
                "--sort",
                "asc",
                "--page-size",
                "50",
                "--format",
                "json"
        );
    }

    @Test
    void parsesDataItemsEnvelope() throws Exception {
        LarkMessageSearchTool tool = new LarkMessageSearchTool(
                new LarkCliClient(command -> new CliCommandResult(0, "{}"), new LarkCliProperties(), objectMapper),
                new LarkCliProperties()
        );

        LarkMessageSearchResult result = tool.parse(objectMapper.readTree("""
                {"data":{"items":[{"message_id":"om_2","chat_name":"采购群","sender":{"id":"ou_2","name":"吴纯瑶","sender_type":"user"},"content":"采购评审结论：先看总价。"}],"has_more":true,"page_token":"next"}}
                """));

        assertThat(result.hasMore()).isTrue();
        assertThat(result.pageToken()).isEqualTo("next");
        assertThat(result.items()).extracting(LarkMessageSearchItem::messageId).containsExactly("om_2");
        assertThat(result.items().get(0).senderName()).isEqualTo("吴纯瑶");
    }

    @Test
    void parsesMessagesInStableChronologicalOrder() throws Exception {
        LarkMessageSearchTool tool = new LarkMessageSearchTool(
                new LarkCliClient(command -> new CliCommandResult(0, "{}"), new LarkCliProperties(), objectMapper),
                new LarkCliProperties()
        );

        LarkMessageSearchResult result = tool.parse(objectMapper.readTree("""
                {"data":{"messages":[
                  {"message_id":"om_3","create_time":"2026-05-03 22:08","content":"后发消息"},
                  {"message_id":"om_1","create_time":"2026-05-03 21:25","content":"先发消息B"},
                  {"message_id":"om_0","create_time":"2026-05-03 21:25","content":"先发消息A"}
                ]}}
                """));

        assertThat(result.items()).extracting(LarkMessageSearchItem::messageId)
                .containsExactly("om_0", "om_1", "om_3");
    }
}
