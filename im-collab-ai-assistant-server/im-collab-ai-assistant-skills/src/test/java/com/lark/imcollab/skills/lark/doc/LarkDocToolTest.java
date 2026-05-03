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
        LarkDocTool tool = new LarkDocTool(dummyCliClient(cliCommands), new LarkCliProperties(), openApiClient, docProperties);

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
        assertThat(openApiClient.calls).hasSize(3);
        assertThat(openApiClient.calls.get(0).path()).isEqualTo("/open-apis/docx/v1/documents");
        assertThat(openApiClient.calls.get(1).path()).startsWith(
                "/open-apis/docx/v1/documents/doc-created/blocks/doc-created/children"
        );
        assertThat(openApiClient.calls.get(2).path()).isEqualTo("/open-apis/drive/v1/metas/batch_query");
        @SuppressWarnings("unchecked")
        Map<String, Object> createBody = (Map<String, Object>) openApiClient.calls.get(0).body();
        assertThat(createBody).containsEntry("title", "Java零基础入门教程");
        @SuppressWarnings("unchecked")
        Map<String, Object> blockBody = (Map<String, Object>) openApiClient.calls.get(1).body();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) blockBody.get("children");
        assertThat(children).extracting(block -> block.get("block_type")).contains(3, 4, 2);
    }

    @Test
    void createDocSplitsLongMarkdownIntoMultipleDocBlocks() {
        List<CliCommand> cliCommands = new ArrayList<>();
        RecordingDocOpenApiClient openApiClient = new RecordingDocOpenApiClient(objectMapper);
        LarkDocProperties docProperties = new LarkDocProperties();
        docProperties.setMaxTextCharsPerBlock(100);
        LarkDocTool tool = new LarkDocTool(dummyCliClient(cliCommands), new LarkCliProperties(), openApiClient, docProperties);

        tool.createDoc("长文档", "正文".repeat(200));

        @SuppressWarnings("unchecked")
        Map<String, Object> blockBody = (Map<String, Object>) openApiClient.calls.get(1).body();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) blockBody.get("children");
        assertThat(children.size()).isGreaterThan(2);
        assertThat(cliCommands).isEmpty();
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
                new LarkDocProperties()
        );

        LarkDocFetchResult result = tool.fetchDoc("https://example.feishu.cn/docx/doc-fetch", "outline", "simple");

        assertThat(result.getDocId()).isEqualTo("doc-fetch");
        assertThat(commands).hasSizeGreaterThanOrEqualTo(2);
        assertThat(commands.get(commands.size() - 1).arguments()).contains("docs", "+fetch", "--doc");
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
