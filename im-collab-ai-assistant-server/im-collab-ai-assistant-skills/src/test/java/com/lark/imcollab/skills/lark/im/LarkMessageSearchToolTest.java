package com.lark.imcollab.skills.lark.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliCommandExecutor;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ArrayDeque;
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
    void keywordAndTimeWindowUseHybridSearchAndTreatWindowAsPrimarySet() {
        List<CliCommand> commands = new ArrayList<>();
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                """
                {"data":{"items":[
                  {"message_id":"om_hit_1","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:06","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"body":{"content":"智能工作流项目马上发版"}},
                  {"message_id":"om_hit_2","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:07","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"body":{"content":"AI Agent 自动化多端协同是核心亮点"}}
                ],"has_more":false}}
                """,
                """
                {"data":{"messages":[
                  {"message_id":"om_sys","msg_type":"system","chat_id":"oc_1","create_time":"2026-05-07 02:05","content":"system message"},
                  {"message_id":"om_hit_1","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:06","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"智能工作流项目马上发版"},
                  {"message_id":"om_hit_3","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:08","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"智能工作流项目的营销方案下周要出初稿"},
                  {"message_id":"om_other","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:09","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"这个和检索词无关"}
                ],"has_more":false}}
                """
        ));
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(0, outputs.removeFirst());
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkMessageSearchTool tool = new LarkMessageSearchTool(
                new LarkCliClient(executor, properties, objectMapper),
                properties
        );

        LarkMessageSearchResult result = tool.searchMessages(
                "智能工作流项目",
                "oc_1",
                "2026-05-07T02:00:00+08:00",
                "2026-05-07T02:10:59+08:00",
                50,
                5
        );

        assertThat(commands).hasSize(2);
        assertThat(commands.get(0).arguments()).contains("+chat-messages-list", "--chat-id", "oc_1");
        assertThat(commands.get(1).arguments()).contains("+messages-search", "--query", "智能工作流项目");
        assertThat(result.items()).extracting(LarkMessageSearchItem::messageId)
                .containsExactly("om_sys", "om_hit_1", "om_hit_3", "om_other", "om_hit_2");
        assertThat(result.windowItemCount()).isEqualTo(2);
        assertThat(result.primaryHitCount()).isEqualTo(4);
    }

    @Test
    void lowRecallKeywordSearchTriggersExpandedQueries() {
        List<CliCommand> commands = new ArrayList<>();
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                """
                {"data":{"messages":[
                  {"message_id":"om_1","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:06","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"Q3智能体项目需要补充风险评估"}
                ],"has_more":false}}
                """,
                """
                {"data":{"messages":[
                  {"message_id":"om_2","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:07","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"智能体联调今天完成"}
                ],"has_more":false}}
                """,
                """
                {"data":{"messages":[
                  {"message_id":"om_3","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:08","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"Q3阶段先交付多端同步能力"}
                ],"has_more":false}}
                """
        ));
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(0, outputs.removeFirst());
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkMessageSearchTool tool = new LarkMessageSearchTool(
                new LarkCliClient(executor, properties, objectMapper),
                properties,
                (userQuery, originalQuery, startTime, endTime, maxQueries) -> List.of("智能体", "Q3"),
                item -> false
        );

        LarkMessageSearchResult result = tool.searchMessages("Q3智能体项目", "oc_1", null, null, 50, 5);

        assertThat(commands).hasSize(3);
        assertThat(commands.get(0).arguments()).contains("+messages-search", "--query", "Q3智能体项目");
        assertThat(commands.get(1).arguments()).contains("+messages-search", "--query", "智能体");
        assertThat(commands.get(2).arguments()).contains("+messages-search", "--query", "Q3");
        assertThat(result.expandedQueryPlan().expandedQueries()).containsExactly("智能体", "Q3");
        assertThat(result.expandedHitCount()).isEqualTo(2);
        assertThat(result.items()).extracting(LarkMessageSearchItem::messageId)
                .containsExactly("om_1", "om_2", "om_3");
    }

    @Test
    void highRecallKeywordSearchDoesNotTriggerExpandedQueries() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(0, """
                    {"data":{"messages":[
                      {"message_id":"om_1","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:01","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"采购评审A"},
                      {"message_id":"om_2","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:02","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"采购评审B"},
                      {"message_id":"om_3","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:03","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"采购评审C"},
                      {"message_id":"om_4","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:04","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"采购评审D"},
                      {"message_id":"om_5","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:05","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"采购评审E"}
                    ],"has_more":false}}
                    """);
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkMessageSearchTool tool = new LarkMessageSearchTool(
                new LarkCliClient(executor, properties, objectMapper),
                properties,
                (userQuery, originalQuery, startTime, endTime, maxQueries) -> List.of("不该触发"),
                item -> false
        );

        LarkMessageSearchResult result = tool.searchMessages("采购评审", "oc_1", null, null, 50, 5);

        assertThat(commands).hasSize(1);
        assertThat(result.expandedQueryPlan().expandedQueries()).isEmpty();
        assertThat(result.primaryHitCount()).isEqualTo(5);
    }

    @Test
    void highRawHitButLowEffectiveHitStillTriggersExpandedQueries() {
        List<CliCommand> commands = new ArrayList<>();
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                """
                {"data":{"messages":[
                  {"message_id":"om_1","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:01","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"@飞书IM- test 根据Q3智能工作流项目的消息来制作汇报用ppt","mentions":[{"key":"u1","id":"ou_bot","id_type":"open_id","name":"飞书IM- test"}]},
                  {"message_id":"om_2","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:02","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"@飞书IM- test 根据Q3智能工作流项目的消息来制作汇报用ppt","mentions":[{"key":"u1","id":"ou_bot","id_type":"open_id","name":"飞书IM- test"}]},
                  {"message_id":"om_3","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:03","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"@飞书IM- test 根据Q3智能工作流项目的消息来制作汇报用ppt","mentions":[{"key":"u1","id":"ou_bot","id_type":"open_id","name":"飞书IM- test"}]},
                  {"message_id":"om_4","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:04","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"@飞书IM- test 根据Q3智能工作流项目的消息来制作汇报用ppt","mentions":[{"key":"u1","id":"ou_bot","id_type":"open_id","name":"飞书IM- test"}]},
                  {"message_id":"om_5","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:05","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"@飞书IM- test 根据Q3智能工作流项目的消息来制作汇报用ppt","mentions":[{"key":"u1","id":"ou_bot","id_type":"open_id","name":"飞书IM- test"}]}
                ],"has_more":false}}
                """,
                """
                {"data":{"messages":[
                  {"message_id":"om_real","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:06","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"智能工作流项目需要尽快同步营销方案"}
                ],"has_more":false}}
                """
        ));
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(0, outputs.removeFirst());
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkMessageSearchTool tool = new LarkMessageSearchTool(
                new LarkCliClient(executor, properties, objectMapper),
                properties,
                (userQuery, originalQuery, startTime, endTime, maxQueries) -> List.of("智能工作流"),
                item -> item != null && item.content() != null && item.content().startsWith("@")
        );

        LarkMessageSearchResult result = tool.searchMessages("Q3智能工作流项目", "oc_1", null, null, 50, 5);

        assertThat(commands).hasSize(2);
        assertThat(commands.get(1).arguments()).contains("+messages-search", "--query", "智能工作流");
        assertThat(result.primaryHitCount()).isEqualTo(5);
        assertThat(result.filteredPrimaryHitCount()).isZero();
        assertThat(result.expandedQueryPlan().expandedQueries()).containsExactly("智能工作流");
    }

    @Test
    void queryMissWithinTimeWindowStillKeepsWindowPrimarySet() {
        List<CliCommand> commands = new ArrayList<>();
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                """
                {"data":{"items":[
                  {"message_id":"om_1","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:04","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"body":{"content":"Q3智能工作流项目已完成联调"}},
                  {"message_id":"om_2","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:05","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"body":{"content":"明天继续补权限收口"}}
                ],"has_more":false}}
                """,
                """
                {"data":{"messages":[],"has_more":false}}
                """
        ));
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(0, outputs.removeFirst());
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkMessageSearchTool tool = new LarkMessageSearchTool(
                new LarkCliClient(executor, properties, objectMapper),
                properties,
                (userQuery, originalQuery, startTime, endTime, maxQueries) -> List.of(),
                item -> false
        );

        LarkMessageSearchResult result = tool.searchMessages(
                "Q3智能体项目",
                "oc_1",
                "2026-05-07T02:00:00+08:00",
                "2026-05-07T02:06:59+08:00",
                50,
                5
        );

        assertThat(commands).hasSize(2);
        assertThat(result.primaryHitCount()).isZero();
        assertThat(result.windowItemCount()).isEqualTo(2);
        assertThat(result.items()).extracting(LarkMessageSearchItem::messageId)
                .containsExactly("om_1", "om_2");
    }

    @Test
    void contextNeighborMessagesArePromotedBelowDirectHits() {
        List<CliCommand> commands = new ArrayList<>();
        ArrayDeque<String> outputs = new ArrayDeque<>(List.of(
                """
                {"data":{"items":[
                  {"message_id":"om_1","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:04","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"body":{"content":"前置上下文"}},
                  {"message_id":"om_2","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:05","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"body":{"content":"Q3智能工作流项目已完成联调"}},
                  {"message_id":"om_3","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:06","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"body":{"content":"后置上下文"}}
                ],"has_more":false}}
                """,
                """
                {"data":{"messages":[
                  {"message_id":"om_2","msg_type":"text","chat_id":"oc_1","create_time":"2026-05-07 02:05","sender":{"id":"ou_1","name":"洪徐博","sender_type":"user"},"content":"Q3智能工作流项目已完成联调"}
                ],"has_more":false}}
                """
        ));
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(0, outputs.removeFirst());
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkMessageSearchTool tool = new LarkMessageSearchTool(
                new LarkCliClient(executor, properties, objectMapper),
                properties
        );

        LarkMessageSearchResult result = tool.searchMessages(
                "智能工作流项目",
                "oc_1",
                "2026-05-07T02:00:00+08:00",
                "2026-05-07T02:06:59+08:00",
                50,
                5
        );

        assertThat(result.contextExpandedCount()).isEqualTo(2);
        assertThat(result.items()).extracting(LarkMessageSearchItem::messageId)
                .containsExactly("om_2", "om_1", "om_3");
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
