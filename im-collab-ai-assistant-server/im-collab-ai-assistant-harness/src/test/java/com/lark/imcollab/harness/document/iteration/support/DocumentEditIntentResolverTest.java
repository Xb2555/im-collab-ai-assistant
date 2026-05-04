package com.lark.imcollab.harness.document.iteration.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentEditIntentResolverTest {

    @Test
    void deleteAuthorInfoAtDocumentHeadUsesMetadataDeleteAction() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "DELETE",
                  "semanticAction": "DELETE_METADATA_AT_DOCUMENT_HEAD",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "DOCUMENT_HEAD",
                    "matchMode": "DOC_START",
                    "quotedText": "作者信息"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("删除开头的作者信息");

        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.DELETE_METADATA_AT_DOCUMENT_HEAD);
        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getQuotedText()).isEqualTo("作者信息");
    }

    @Test
    void genericDeleteSentenceFallsBackToInlineDelete() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "DELETE",
                  "semanticAction": "DELETE_INLINE_TEXT",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "TEXT",
                    "matchMode": "BY_QUOTED_TEXT",
                    "quotedText": "这句话"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("删除这句话");

        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.DELETE_INLINE_TEXT);
    }

    @Test
    void rewriteMetadataAtHeadUsesDedicatedSemanticAction() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "semanticAction": "REWRITE_METADATA_AT_DOCUMENT_HEAD",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "DOCUMENT_HEAD",
                    "matchMode": "DOC_START",
                    "quotedText": "作者"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("修改文章开头的作者信息为李四");

        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.REWRITE_METADATA_AT_DOCUMENT_HEAD);
    }

    @Test
    void llmParseFailureReturnsClarificationNeeded() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("not valid json");
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("做点什么");

        assertThat(intent.isClarificationNeeded()).isTrue();
        assertThat(intent.getClarificationHint()).isNotBlank();
    }
}
