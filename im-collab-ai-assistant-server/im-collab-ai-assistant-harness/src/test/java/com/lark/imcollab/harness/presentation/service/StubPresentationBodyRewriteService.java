package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.model.entity.PresentationEditOperation;
import org.springframework.ai.chat.model.ChatModel;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StubPresentationBodyRewriteService extends PresentationBodyRewriteService {

    private final String rewrittenText;

    StubPresentationBodyRewriteService(String rewrittenText) {
        super(mockChatModel(rewrittenText));
        this.rewrittenText = rewrittenText;
    }

    @Override
    public String rewrite(String originalText, PresentationEditOperation operation) {
        return rewrittenText;
    }

    private static ChatModel mockChatModel(String rewrittenText) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn(rewrittenText);
        return chatModel;
    }
}
