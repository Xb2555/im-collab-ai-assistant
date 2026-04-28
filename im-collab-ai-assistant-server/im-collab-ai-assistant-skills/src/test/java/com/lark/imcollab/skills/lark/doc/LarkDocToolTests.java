package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LarkDocToolTests {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        this.objectMapper = new ObjectMapper();
    }

    @Test
    void shouldParseCreateDocResponse() throws Exception {
        LarkCliClient client = stubClient("""
                {"data":{"doc_id":"doxcn123","doc_url":"https://example/docx/doxcn123","message":"ok"}}
                """);
        LarkDocTool tool = new LarkDocTool(client);
        LarkDocCreateResult result = tool.createDoc("测试文档", "## 正文");

        assertThat(result.getDocId()).isEqualTo("doxcn123");
        assertThat(result.getDocUrl()).contains("doxcn123");
    }

    @Test
    void shouldParseAppendDocResponse() throws Exception {
        LarkCliClient client = stubClient("""
                {"success":true,"data":{"doc_id":"doxcn123","mode":"append","message":"updated","board_tokens":["b1"]}}
                """);
        LarkDocTool tool = new LarkDocTool(client);
        LarkDocUpdateResult result = tool.appendMarkdown("doxcn123", "## 追加");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getBoardTokens()).containsExactly("b1");
    }

    private LarkCliClient stubClient(String output) {
        return new LarkCliClient(null, null, objectMapper) {
            @Override
            public CliCommandResult execute(List<String> args) {
                return new CliCommandResult(0, output);
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode readJsonOutput(String content) throws IOException {
                return objectMapper.readTree(content);
            }
        };
    }
}
