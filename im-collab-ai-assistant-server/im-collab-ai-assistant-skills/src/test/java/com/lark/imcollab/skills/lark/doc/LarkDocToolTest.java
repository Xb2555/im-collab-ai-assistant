package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliCommandExecutor;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkBotMessageProperties;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import com.lark.imcollab.skills.lark.config.LarkDocProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LarkDocToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createDocUsesDocxOpenApiInsteadOfCliCreate() {
        List<CliCommand> cliCommands = new ArrayList<>();
        RecordingDocOpenApiClient openApiClient = new RecordingDocOpenApiClient(objectMapper);
        LarkDocProperties docProperties = new LarkDocProperties();
        docProperties.setWebBaseUrl("https://tenant.feishu.cn");
        LarkDocTool tool = new LarkDocTool(
                dummyCliClient(cliCommands),
                new LarkCliProperties(),
                openApiClient,
                docProperties,
                objectMapper
        );

        LarkDocCreateResult result = tool.createDoc("Java零基础入门教程", """
                ## 学习路线
                先学习变量和控制流。

                ```java
                System.out.println("hello");
                ```
                """);

        assertThat(cliCommands).isEmpty();
        assertThat(result.getDocId()).isEqualTo("doc-created");
        assertThat(result.getDocUrl()).isEqualTo("https://tenant.feishu.cn/docx/doc-created-from-meta");
        assertThat(openApiClient.calls).hasSize(4);
        assertThat(openApiClient.calls.get(0).path()).isEqualTo("/open-apis/docx/v1/documents");
        assertThat(openApiClient.calls.get(1).path()).isEqualTo("/open-apis/docx/v1/documents/blocks/convert");
        assertThat(openApiClient.calls.get(2).path()).startsWith(
                "/open-apis/docx/v1/documents/doc-created/blocks/doc-created/descendant"
        );
        assertThat(openApiClient.calls.get(3).path()).isEqualTo("/open-apis/drive/v1/metas/batch_query");
        @SuppressWarnings("unchecked")
        Map<String, Object> createBody = (Map<String, Object>) openApiClient.calls.get(0).body();
        assertThat(createBody).containsEntry("title", "Java零基础入门教程");
        @SuppressWarnings("unchecked")
        Map<String, Object> convertBody = (Map<String, Object>) openApiClient.calls.get(1).body();
        assertThat(convertBody).containsEntry("content_type", "markdown");
        assertThat((String) convertBody.get("content")).contains("```java");
        @SuppressWarnings("unchecked")
        Map<String, Object> blockBody = (Map<String, Object>) openApiClient.calls.get(2).body();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) blockBody.get("descendants");
        assertThat((List<String>) blockBody.get("children_id")).containsExactly("block-h2", "block-code");
        assertThat(children).extracting(block -> block.get("block_id")).contains("block-h2", "block-code");
        assertThat(children.toString()).doesNotContain("merge_info");
    }

    @Test
    void createDocWithMermaidUpgradesDiagramToWhiteboard() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            List<String> args = command.arguments();
            if (args.contains("+update") && args.contains("--help")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +update [flags]

                        Flags:
                              --as string
                              --doc string
                              --mode string
                              --markdown string
                        """);
            }
            if (args.contains("+whiteboard-update") && args.contains("--help")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +whiteboard-update [flags]

                        Flags:
                              --as string
                              --whiteboard-token string
                              --input_format string
                              --source string
                              --overwrite
                              --yes
                        """);
            }
            if (args.contains("+update")) {
                assertThat(command.stdin()).contains("<whiteboard type=\"blank\"></whiteboard>");
                return new CliCommandResult(0, """
                        {"success":true,"data":{"doc_id":"doc-created","mode":"overwrite","revision_id":2,"board_tokens":["wb-1"]}}
                        """);
            }
            if (args.contains("+whiteboard-update")) {
                assertThat(args).contains("--whiteboard-token", "wb-1", "--input_format", "mermaid", "--source", "-");
                assertThat(command.stdin()).contains("graph TD");
                return new CliCommandResult(0, """
                        {"success":true,"data":{"message":"whiteboard updated"}}
                        """);
            }
            return new CliCommandResult(1, "unexpected cli command");
        };
        RecordingDocOpenApiClient openApiClient = new RecordingDocOpenApiClient(objectMapper);
        LarkDocTool tool = new LarkDocTool(
                new LarkCliClient(executor, new LarkCliProperties(), objectMapper),
                new LarkCliProperties(),
                openApiClient,
                new LarkDocProperties(),
                objectMapper
        );

        LarkDocCreateResult result = tool.createDoc("架构设计", """
                ## 系统架构图

                ```mermaid
                graph TD
                    A[用户] --> B[服务]
                ```
                """);

        assertThat(result.getDocId()).isEqualTo("doc-created");
        assertThat(openApiClient.calls).hasSize(2);
        assertThat(openApiClient.calls.get(0).path()).isEqualTo("/open-apis/docx/v1/documents");
        assertThat(openApiClient.calls.get(1).path()).isEqualTo("/open-apis/drive/v1/metas/batch_query");
        assertThat(commands).hasSize(4);
        assertThat(commands.get(0).arguments()).contains("docs", "+update", "--help");
        assertThat(commands.get(1).arguments()).contains("docs", "+update", "--doc", "doc-created", "--mode", "overwrite", "--markdown", "-");
        assertThat(commands.get(2).arguments()).contains("docs", "+whiteboard-update", "--help");
        assertThat(commands.get(3).arguments()).contains("docs", "+whiteboard-update", "--whiteboard-token", "wb-1");
    }

    @Test
    void createDocSplitsLongMarkdownIntoMultipleDocBlocks() {
        List<CliCommand> cliCommands = new ArrayList<>();
        RecordingDocOpenApiClient openApiClient = new RecordingDocOpenApiClient(objectMapper);
        LarkDocProperties docProperties = new LarkDocProperties();
        docProperties.setMaxTextCharsPerBlock(100);
        LarkDocTool tool = new LarkDocTool(
                dummyCliClient(cliCommands),
                new LarkCliProperties(),
                openApiClient,
                docProperties,
                objectMapper
        );

        tool.createDoc("长文档", "正文".repeat(200));

        @SuppressWarnings("unchecked")
        Map<String, Object> blockBody = (Map<String, Object>) openApiClient.calls.get(2).body();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) blockBody.get("descendants");
        assertThat(children).isNotEmpty();
        assertThat(cliCommands).isEmpty();
    }

    @Test
    void createDocKeepsConvertedBlockSubtreeInSameRequestWhenBatching() {
        List<CliCommand> cliCommands = new ArrayList<>();
        RecordingDocOpenApiClient openApiClient = new RecordingDocOpenApiClient(objectMapper);
        LarkDocProperties docProperties = new LarkDocProperties();
        docProperties.setMaxBlocksPerRequest(1);
        LarkDocTool tool = new LarkDocTool(
                dummyCliClient(cliCommands),
                new LarkCliProperties(),
                openApiClient,
                docProperties,
                objectMapper
        );

        tool.createDoc("嵌套块", "nested-split");

        List<Call> insertCalls = openApiClient.calls.stream()
                .filter(call -> call.path().contains("/descendant"))
                .toList();
        assertThat(insertCalls).hasSize(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> firstBody = (Map<String, Object>) insertCalls.get(0).body();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstDescendants = (List<Map<String, Object>>) firstBody.get("descendants");
        assertThat((List<String>) firstBody.get("children_id")).containsExactly("root-a");
        assertThat(firstDescendants).extracting(block -> block.get("block_id"))
                .containsExactly("root-a", "child-a");
        @SuppressWarnings("unchecked")
        Map<String, Object> secondBody = (Map<String, Object>) insertCalls.get(1).body();
        assertThat((List<String>) secondBody.get("children_id")).containsExactly("root-b");
    }

    @Test
    void fetchDocStillUsesCliDuringReadMigration() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            if (command.arguments().contains("--help")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +fetch [flags]

                        Flags:
                              --api-version string
                              --as string
                              --doc string
                              --format string
                        """);
            }
            return new CliCommandResult(0, """
                    {"data":{"document":{"document_id":"doc-fetch","content":"正文"}}}
                    """);
        };
        LarkDocTool tool = new LarkDocTool(
                new LarkCliClient(executor, new LarkCliProperties(), objectMapper),
                new LarkCliProperties(),
                new RecordingDocOpenApiClient(objectMapper),
                new LarkDocProperties(),
                objectMapper
        );

        LarkDocFetchResult result = tool.fetchDoc("https://example.feishu.cn/docx/doc-fetch", "outline", "simple");

        assertThat(result.getDocId()).isEqualTo("doc-fetch");
        assertThat(commands).hasSizeGreaterThanOrEqualTo(2);
        assertThat(commands.get(commands.size() - 1).arguments()).contains("docs", "+fetch", "--doc");
    }

    @Test
    void updateDocRetriesWithoutAsWhenCliRejectsFlag() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            List<String> args = command.arguments();
            if (args.contains("--help")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +update [flags]

                        Flags:
                              --api-version string
                              --as string
                              --doc string
                              --command string
                              --doc-format string
                              --content string
                        """);
            }
            if (args.contains("--as")) {
                return new CliCommandResult(1, """
                        {"error":{"message":"unknown flag: --as"}}
                        """);
            }
            return new CliCommandResult(0, """
                    {"data":{"document_id":"doc-1","url":"https://example.feishu.cn/docx/doc-1","title":"文档","message":"ok"}} 
                    """);
        };
        LarkDocTool tool = new LarkDocTool(
                new LarkCliClient(executor, new LarkCliProperties(), objectMapper),
                new LarkCliProperties(),
                new RecordingDocOpenApiClient(objectMapper),
                new LarkDocProperties(),
                objectMapper
        );

        LarkDocUpdateResult result = tool.updateDoc("doc-1", "append", "新增内容");

        assertThat(result.getDocId()).isEqualTo("doc-1");
        assertThat(commands).hasSizeGreaterThanOrEqualTo(4);
        assertThat(commands.get(2).arguments()).contains("--as");
        assertThat(commands.get(commands.size() - 1).arguments()).doesNotContain("--as");
    }

    @Test
    void updateDocAppendUsesCommandProtocol() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            if (command.arguments().contains("--help")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +update [flags]

                        Flags:
                        --api-version string
                        --as string
                        --doc string
                        --command string
                        --markdown string
                        """);
            }
            return new CliCommandResult(0, """
                    {"success":true,"data":{"doc_id":"doc-append","mode":"append","message":"ok","revision_id":2}}
                    """);
        };
        LarkDocTool tool = new LarkDocTool(
                new LarkCliClient(executor, new LarkCliProperties(), objectMapper),
                new LarkCliProperties(),
                new RecordingDocOpenApiClient(objectMapper),
                new LarkDocProperties(),
                objectMapper
        );

        LarkDocUpdateResult result = tool.appendMarkdown("doc-append", "新增内容");

        assertThat(result.getDocId()).isEqualTo("doc-append");
        CliCommand updateCommand = commands.get(commands.size() - 1);
        assertThat(updateCommand.arguments()).contains("docs", "+update", "--command", "append", "--markdown", "-");
        assertThat(updateCommand.arguments()).doesNotContain("--mode");
        assertThat(updateCommand.stdin()).isEqualTo("新增内容");
    }

    @Test
    void updateByCommandStrReplaceUsesCommandProtocol() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            if (command.arguments().contains("--help")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +update [flags]

                        Flags:
                              --api-version string
                              --as string
                              --doc string
                              --command string
                              --pattern string
                              --content string
                        """);
            }
            return new CliCommandResult(0, """
                    {"success":true,"data":{"doc_id":"doc-str","mode":"str_replace","message":"ok","revision_id":3}}
                    """);
        };
        LarkDocTool tool = new LarkDocTool(
                new LarkCliClient(executor, new LarkCliProperties(), objectMapper),
                new LarkCliProperties(),
                new RecordingDocOpenApiClient(objectMapper),
                new LarkDocProperties(),
                objectMapper
        );

        LarkDocUpdateResult result = tool.updateByCommand("doc-str", "str_replace", "新内容", "markdown", null, "旧内容", null);

        assertThat(result.getDocId()).isEqualTo("doc-str");
        CliCommand updateCommand = commands.get(commands.size() - 1);
        assertThat(updateCommand.arguments()).contains("docs", "+update", "--command", "str_replace");
        assertThat(updateCommand.arguments()).doesNotContain("--mode");
    }

    @Test
    void updateByCommandBlockInsertAfterUsesCommandProtocol() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            if (command.arguments().contains("--help")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +update [flags]

                        Flags:
                              --api-version string
                              --as string
                              --doc string
                              --command string
                              --block-id string
                              --content string
                        """);
            }
            return new CliCommandResult(0, """
                    {"success":true,"data":{"doc_id":"doc-block","mode":"block_insert_after","message":"ok","revision_id":4}}
                    """);
        };
        LarkDocTool tool = new LarkDocTool(
                new LarkCliClient(executor, new LarkCliProperties(), objectMapper),
                new LarkCliProperties(),
                new RecordingDocOpenApiClient(objectMapper),
                new LarkDocProperties(),
                objectMapper
        );

        LarkDocUpdateResult result = tool.updateByCommand("doc-block", "block_insert_after", "内容", "markdown", "blk-1", null, null);

        assertThat(result.getDocId()).isEqualTo("doc-block");
        CliCommand updateCommand = commands.get(commands.size() - 1);
        assertThat(updateCommand.arguments()).contains("docs", "+update", "--command", "block_insert_after");
        assertThat(updateCommand.arguments()).doesNotContain("--mode");
    }

    @Test
    void updateByCommandBlockReplaceNormalizesImageDocFormat() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            if (command.arguments().contains("--help")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +update [flags]

                        Flags:
                              --api-version string
                              --as string
                              --doc string
                              --command string
                              --block-id string
                              --doc-format string
                              --content string
                        """);
            }
            return new CliCommandResult(0, """
                    {"success":true,"data":{"doc_id":"doc-block","mode":"block_replace","message":"ok","revision_id":5}}
                    """);
        };
        LarkDocTool tool = new LarkDocTool(
                new LarkCliClient(executor, new LarkCliProperties(), objectMapper),
                new LarkCliProperties(),
                new RecordingDocOpenApiClient(objectMapper),
                new LarkDocProperties(),
                objectMapper
        );

        LarkDocUpdateResult result = tool.updateByCommand("doc-block", "block_replace", "内容", "image", "blk-1", null, null);

        assertThat(result.getDocId()).isEqualTo("doc-block");
        CliCommand updateCommand = commands.get(commands.size() - 1);
        assertThat(updateCommand.arguments()).contains("docs", "+update", "--command", "block_replace", "--doc-format", "markdown");
        assertThat(updateCommand.arguments()).doesNotContain("--mode");
    }

    @Test
    void updateByCommandBlockMoveAfterUsesSrcBlockIdsAndTargetBlockId() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            if (command.arguments().contains("--help")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +update [flags]

                        Flags:
                              --api-version string
                              --as string
                              --doc string
                              --command string
                              --block-id string
                              --src-block-ids string
                        """);
            }
            return new CliCommandResult(0, """
                    {"success":true,"data":{"doc_id":"doc-block","mode":"block_move_after","message":"ok","revision_id":5}}
                    """);
        };
        LarkDocTool tool = new LarkDocTool(
                new LarkCliClient(executor, new LarkCliProperties(), objectMapper),
                new LarkCliProperties(),
                new RecordingDocOpenApiClient(objectMapper),
                new LarkDocProperties(),
                objectMapper
        );

        LarkDocUpdateResult result = tool.updateByCommand("doc-block", "block_move_after", null, null, "blk-1", "target-1", null);

        assertThat(result.getDocId()).isEqualTo("doc-block");
        CliCommand updateCommand = commands.get(commands.size() - 1);
        assertThat(updateCommand.arguments()).contains("docs", "+update", "--command", "block_move_after", "--block-id", "target-1", "--src-block-ids", "blk-1");
        assertThat(updateCommand.arguments()).doesNotContain("--target-block-id", "--pattern");
    }

    private LarkCliClient dummyCliClient(List<CliCommand> commands) {
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(1, "CLI should not be used by createDoc");
        };
        return new LarkCliClient(executor, new LarkCliProperties(), objectMapper);
    }

    private static class RecordingDocOpenApiClient extends LarkDocOpenApiClient {

        private final ObjectMapper objectMapper;
        private final List<Call> calls = new ArrayList<>();

        RecordingDocOpenApiClient(ObjectMapper objectMapper) {
            super(new LarkBotMessageProperties(), objectMapper);
            this.objectMapper = objectMapper;
        }

        @Override
        public JsonNode post(String pathWithQuery, Object body, int timeoutSeconds) {
            calls.add(new Call(pathWithQuery, body));
            try {
                if (pathWithQuery.equals("/open-apis/docx/v1/documents")) {
                    return objectMapper.readTree("""
                            {"document":{"document_id":"doc-created","revision_id":1,"title":"created"}}
                            """);
                }
                if (pathWithQuery.equals("/open-apis/docx/v1/documents/blocks/convert")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> request = (Map<String, Object>) body;
                    if (String.valueOf(request.get("content")).contains("nested-split")) {
                        return objectMapper.readTree("""
                                {
                                  "first_level_block_ids": ["root-a", "root-b"],
                                  "blocks": [
                                    {
                                      "block_id": "root-a",
                                      "block_type": 2,
                                      "text": {"elements": []},
                                      "children": ["child-a"]
                                    },
                                    {
                                      "block_id": "child-a",
                                      "block_type": 2,
                                      "text": {"elements": []},
                                      "children": []
                                    },
                                    {
                                      "block_id": "root-b",
                                      "block_type": 2,
                                      "text": {"elements": []},
                                      "children": []
                                    }
                                  ]
                                }
                                """);
                    }
                    return objectMapper.readTree("""
                            {
                              "first_level_block_ids": ["block-h2", "block-code"],
                              "blocks": [
                                {
                                  "block_id": "block-h2",
                                  "block_type": 4,
                                  "heading2": {"elements": []},
                                  "children": []
                                },
                                {
                                  "block_id": "block-code",
                                  "block_type": 2,
                                  "text": {"elements": []},
                                  "children": [],
                                  "table": {"merge_info": [{"row_span": 1}]}
                                }
                              ]
                            }
                            """);
                }
                if (pathWithQuery.equals("/open-apis/drive/v1/metas/batch_query")) {
                    return objectMapper.readTree("""
                            {"metas":[{"doc_token":"doc-created","url":"https://tenant.feishu.cn/docx/doc-created-from-meta"}]}
                            """);
                }
                return objectMapper.readTree("""
                        {"children":[]}
                        """);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private record Call(String path, Object body) {
    }
}
