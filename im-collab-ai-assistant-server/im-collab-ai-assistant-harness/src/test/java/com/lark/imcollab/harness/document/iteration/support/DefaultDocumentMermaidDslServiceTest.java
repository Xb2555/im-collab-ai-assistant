package com.lark.imcollab.harness.document.iteration.support;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDocumentMermaidDslServiceTest {

    @Test
    void dataFlowPromptRequiresSequenceDiagram() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(contains("第一行必须严格以 `sequenceDiagram` 开头")))
                .thenReturn("""
                        sequenceDiagram
                            participant User
                            participant Agent
                            User->>Agent: 请求
                        """);
        DefaultDocumentMermaidDslService service = new DefaultDocumentMermaidDslService(chatModel);

        String result = service.generateMermaidDsl("请在 4.2 后插入一张数据流转图");

        assertThat(result).startsWith("sequenceDiagram");
        verify(chatModel).call(contains("图类型：DATA_FLOW"));
    }

    @Test
    void architecturePromptRequiresFlowchart() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(contains("第一行必须严格以 `flowchart TB` 开头")))
                .thenReturn("""
                        flowchart TB
                            A --> B
                        """);
        DefaultDocumentMermaidDslService service = new DefaultDocumentMermaidDslService(chatModel);

        String result = service.generateMermaidDsl("请在 3.3.2 后插入一张架构图");

        assertThat(result).startsWith("flowchart TB");
        verify(chatModel).call(contains("图类型：CONTEXT"));
    }
}
