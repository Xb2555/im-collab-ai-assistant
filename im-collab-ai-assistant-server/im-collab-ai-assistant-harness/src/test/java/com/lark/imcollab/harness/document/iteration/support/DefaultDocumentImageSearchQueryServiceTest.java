package com.lark.imcollab.harness.document.iteration.support;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultDocumentImageSearchQueryServiceTest {

    @Test
    void derivesQueryFromModelOutput() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("长城");
        DefaultDocumentImageSearchQueryService service = new DefaultDocumentImageSearchQueryService(chatModel);

        assertThat(service.deriveQuery("在2.1后插入一张长城的图片")).isEqualTo("长城");
    }
}
