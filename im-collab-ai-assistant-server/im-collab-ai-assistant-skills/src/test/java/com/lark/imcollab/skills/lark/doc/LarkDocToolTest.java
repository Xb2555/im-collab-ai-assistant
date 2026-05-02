package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliCommandExecutor;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LarkDocToolTest {

    @Test
    void fetchDocUsesLarkCliFetchV2() {
        AtomicReference<CliCommand> captured = new AtomicReference<>();
        CliCommandExecutor executor = command -> {
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
                "--doc", "https://example.feishu.cn/docx/doc-token",
                "--scope", "outline",
                "--detail", "simple"
        ));
    }
}
