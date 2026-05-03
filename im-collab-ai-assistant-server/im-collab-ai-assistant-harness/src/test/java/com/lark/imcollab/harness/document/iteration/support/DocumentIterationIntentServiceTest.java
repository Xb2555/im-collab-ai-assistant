package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentIterationIntentServiceTest {

    @Test
    void returnsEnumChosenByModel() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("DELETE");
        DocumentIterationIntentService service = new DocumentIterationIntentService(chatModel);

        assertThat(service.resolve("把项目背景与问题这一节删了"))
                .isEqualTo(DocumentIterationIntentType.DELETE);
    }

    @Test
    void rejectsInvalidModelOutput() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("删除这一节");
        DocumentIterationIntentService service = new DocumentIterationIntentService(chatModel);

        assertThatThrownBy(() -> service.resolve("把项目背景与问题这一节删了"))
                .isInstanceOf(AiAssistantException.class)
                .hasMessageContaining("非法枚举值");
    }

    @Test
    void rejectsBlankInstruction() {
        ChatModel chatModel = mock(ChatModel.class);
        DocumentIterationIntentService service = new DocumentIterationIntentService(chatModel);

        assertThatThrownBy(() -> service.resolve(" "))
                .isInstanceOf(AiAssistantException.class)
                .hasMessageContaining("instruction must be provided");
    }
}
