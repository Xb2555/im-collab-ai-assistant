package com.lark.imcollab.harness.presentation.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationIterationIntentType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PresentationEditIntentResolverTest {

    @Test
    void resolvesNaturalFirstPageTitleInstruction() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "actionType": "REPLACE_SLIDE_TITLE",
                  "pageIndex": 1,
                  "replacementText": "7878",
                  "clarificationNeeded": false
                }
                """);
        PresentationEditIntentResolver resolver = new PresentationEditIntentResolver(chatModel, new ObjectMapper());

        PresentationEditIntent intent = resolver.resolve("帮我修改第一页标题为7878");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getIntentType()).isEqualTo(PresentationIterationIntentType.UPDATE_CONTENT);
        assertThat(intent.getActionType()).isEqualTo(PresentationEditActionType.REPLACE_SLIDE_TITLE);
        assertThat(intent.getPageIndex()).isEqualTo(1);
        assertThat(intent.getReplacementText()).isEqualTo("7878");
    }

    @Test
    void missingPageOrReplacementFallsBackToClarification() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "actionType": "REPLACE_SLIDE_TITLE",
                  "clarificationNeeded": false
                }
                """);
        PresentationEditIntentResolver resolver = new PresentationEditIntentResolver(chatModel, new ObjectMapper());

        PresentationEditIntent intent = resolver.resolve("改一下 PPT");

        assertThat(intent.isClarificationNeeded()).isTrue();
        assertThat(intent.getClarificationHint()).isNotBlank();
    }
}
