package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LarkDocToolTests {

    @Test
    void shouldParseCreateDocResponse() {
        LarkCliClient client = mock(LarkCliClient.class);
        when(client.execute(anyList())).thenReturn(new CliCommandResult(0, """
                {"data":{"doc_id":"doxcn123","doc_url":"https://example/docx/doxcn123","message":"ok"}}
                """));
        when(client.readJsonOutput(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> new ObjectMapper().readTree(invocation.getArgument(0)));

        LarkDocTool tool = new LarkDocTool(client);
        LarkDocCreateResult result = tool.createDoc("测试文档", "## 正文");

        assertThat(result.getDocId()).isEqualTo("doxcn123");
        assertThat(result.getDocUrl()).contains("doxcn123");
    }

    @Test
    void shouldParseAppendDocResponse() {
        LarkCliClient client = mock(LarkCliClient.class);
        when(client.execute(anyList())).thenReturn(new CliCommandResult(0, """
                {"success":true,"data":{"doc_id":"doxcn123","mode":"append","message":"updated","board_tokens":["b1"]}}
                """));
        when(client.readJsonOutput(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> new ObjectMapper().readTree(invocation.getArgument(0)));

        LarkDocTool tool = new LarkDocTool(client);
        LarkDocUpdateResult result = tool.appendMarkdown("doxcn123", "## 追加");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getBoardTokens()).containsExactly("b1");
    }
}
