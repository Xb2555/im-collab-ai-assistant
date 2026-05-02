package com.lark.imcollab.skills.lark.doc;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LarkDocToolTest {

    @Test
    void createDocUsesOnlySupportedCreateFlags() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            if (command.arguments().contains("--help")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +create [flags]

                        Flags:
                              --as string
                              --markdown string
                              --title string
                        """);
            }
            return new CliCommandResult(0, """
                    {"data":{"document":{"document_id":"doc-1","url":"https://example.feishu.cn/docx/doc-1"}}}
                    """);
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkDocTool tool = new LarkDocTool(new LarkCliClient(executor, properties, new ObjectMapper()), properties);

        LarkDocCreateResult result = tool.createDoc("测试文档", "正文");

        assertThat(result.getDocUrl()).contains("doc-1");
        List<String> createArgs = commands.get(1).arguments();
        assertThat(createArgs).containsSequence(List.of(
                "docs", "+create",
                "--as", "user",
                "--title", "测试文档",
                "--markdown"
        ));
        assertThat(createArgs).doesNotContain("--api-version", "--doc-format", "--content");
    }

    @Test
    void createDocUsesVersionedHelpWhenApiVersionChangesFlags() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            if (command.arguments().contains("--help")
                    && command.arguments().contains("--api-version")
                    && command.arguments().contains("v2")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +create [flags]

                        Flags:
                              --api-version string
                              --as string
                              --content string
                              --doc-format string
                        """);
            }
            if (command.arguments().contains("--help")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +create [flags]

                        Flags:
                              --api-version string
                              --as string
                              --markdown string
                              --title string
                        """);
            }
            return new CliCommandResult(0, """
                    {"data":{"document":{"document_id":"doc-2","url":"https://example.feishu.cn/docx/doc-2"}}}
                    """);
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkDocTool tool = new LarkDocTool(new LarkCliClient(executor, properties, new ObjectMapper()), properties);

        LarkDocCreateResult result = tool.createDoc("v2文档", "正文");

        assertThat(result.getDocUrl()).contains("doc-2");
        List<String> createArgs = commands.get(2).arguments();
        assertThat(createArgs).containsSequence(List.of(
                "docs", "+create",
                "--as", "user",
                "--api-version", "v2",
                "--doc-format", "markdown",
                "--content"
        ));
        assertThat(createArgs).doesNotContain("--title", "--markdown");
    }

    @Test
    void createDocFallsBackToClassicFlagsWhenRuntimeRejectsVersionedFlags() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            if (command.arguments().contains("--help")
                    && command.arguments().contains("--api-version")
                    && command.arguments().contains("v2")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +create [flags]

                        Flags:
                              --api-version string
                              --as string
                              --content string
                              --doc-format string
                        """);
            }
            if (command.arguments().contains("--help")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +create [flags]

                        Flags:
                              --api-version string
                              --as string
                              --markdown string
                              --title string
                        """);
            }
            if (command.arguments().contains("--content") || command.arguments().contains("--doc-format")) {
                return new CliCommandResult(1, "Error: unknown flag: --api-version");
            }
            return new CliCommandResult(0, """
                    {"data":{"document":{"document_id":"doc-3","url":"https://example.feishu.cn/docx/doc-3"}}}
                    """);
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkDocTool tool = new LarkDocTool(new LarkCliClient(executor, properties, new ObjectMapper()), properties);

        LarkDocCreateResult result = tool.createDoc("兼容文档", "正文");

        assertThat(result.getDocUrl()).contains("doc-3");
        List<String> createArgs = commands.get(commands.size() - 1).arguments();
        assertThat(createArgs).containsSequence(List.of(
                "docs", "+create",
                "--as", "user",
                "--title", "兼容文档",
                "--markdown"
        ));
        assertThat(createArgs).doesNotContain("--api-version", "--content", "--doc-format");
    }

    @Test
    void fetchDocUsesOnlySupportedFetchFlags() {
        AtomicReference<CliCommand> captured = new AtomicReference<>();
        CliCommandExecutor executor = command -> {
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
            captured.set(command);
            return new CliCommandResult(0, """
                    {"data":{"document":{"title":"方案文档","content":"项目背景与目标"}}}
                    """);
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkDocTool tool = new LarkDocTool(new LarkCliClient(executor, properties, new ObjectMapper()), properties);

        LarkDocFetchResult result = tool.fetchDoc("https://example.feishu.cn/docx/doc-token", "outline", "simple");

        assertThat(result.getTitle()).isEqualTo("方案文档");
        assertThat(result.getContent()).contains("项目背景");
        assertThat(captured.get().arguments()).containsSequence(List.of(
                "docs", "+fetch",
                "--as", "user",
                "--api-version", "v2",
                "--doc", "https://example.feishu.cn/docx/doc-token"
        ));
        assertThat(captured.get().arguments()).doesNotContain("--scope", "--detail");
    }

    @Test
    void fetchDocRetriesWithoutApiVersionWhenRuntimeRejectsIt() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            if (command.arguments().contains("--help")
                    && command.arguments().contains("--api-version")
                    && command.arguments().contains("v2")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +fetch [flags]

                        Flags:
                              --api-version string
                              --as string
                              --doc string
                              --scope string
                              --detail string
                        """);
            }
            if (command.arguments().contains("--help")) {
                return new CliCommandResult(0, """
                        Usage:
                          lark-cli docs +fetch [flags]

                        Flags:
                              --api-version string
                              --as string
                              --doc string
                        """);
            }
            if (command.arguments().contains("--api-version")) {
                return new CliCommandResult(1, "Error: unknown flag: --api-version");
            }
            return new CliCommandResult(0, """
                    {"data":{"document":{"title":"方案文档","content":"项目背景与目标"}}}
                    """);
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkDocTool tool = new LarkDocTool(new LarkCliClient(executor, properties, new ObjectMapper()), properties);

        LarkDocFetchResult result = tool.fetchDoc("https://example.feishu.cn/docx/doc-token", "outline", "simple");

        assertThat(result.getTitle()).isEqualTo("方案文档");
        List<String> fetchArgs = commands.get(commands.size() - 1).arguments();
        assertThat(fetchArgs).containsSequence(List.of(
                "docs", "+fetch",
                "--as", "user",
                "--doc", "https://example.feishu.cn/docx/doc-token"
        ));
        assertThat(fetchArgs).doesNotContain("--api-version");
    }
}
