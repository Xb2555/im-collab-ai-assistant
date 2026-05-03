package com.lark.imcollab.harness.document.iteration.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
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
        DocumentIterationIntentService intentService = mock(DocumentIterationIntentService.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(intentService.resolve("删除开头的作者信息")).thenReturn(DocumentIterationIntentType.DELETE);
        when(chatModel.call(anyString())).thenReturn("""
                {"targetRegion":"document_head","targetSemantic":"metadata","targetKeywords":["作者信息"]}
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(intentService, chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("删除开头的作者信息");

        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.DELETE_METADATA_AT_DOCUMENT_HEAD);
        assertThat(intent.getParameters().get("targetRegion")).isEqualTo("document_head");
        assertThat(intent.getParameters().get("targetSemantic")).isEqualTo("metadata");
        assertThat(intent.getParameters().get("targetKeywords")).isEqualTo("作者信息");
    }

    @Test
    void genericDeleteSentenceFallsBackToInlineDeleteInsteadOfBlockDelete() {
        DocumentIterationIntentService intentService = mock(DocumentIterationIntentService.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(intentService.resolve("删除这句话")).thenReturn(DocumentIterationIntentType.DELETE);
        when(chatModel.call(anyString())).thenReturn("""
                {"targetRegion":"inline","targetSemantic":"paragraph","targetKeywords":["这句话"]}
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(intentService, chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("删除这句话");

        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.DELETE_INLINE_TEXT);
    }

    @Test
    void rewriteMetadataAtHeadUsesDedicatedSemanticAction() {
        DocumentIterationIntentService intentService = mock(DocumentIterationIntentService.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(intentService.resolve("修改文章开头的作者信息为李四")).thenReturn(DocumentIterationIntentType.UPDATE_CONTENT);
        when(chatModel.call(anyString())).thenReturn("""
                {"targetRegion":"document_head","targetSemantic":"metadata","targetKeywords":["作者信息","作者"]}
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(intentService, chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("修改文章开头的作者信息为李四");

        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.REWRITE_METADATA_AT_DOCUMENT_HEAD);
    }
}
