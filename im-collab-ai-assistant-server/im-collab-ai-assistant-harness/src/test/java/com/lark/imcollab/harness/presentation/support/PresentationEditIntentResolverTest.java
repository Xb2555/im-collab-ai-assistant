package com.lark.imcollab.harness.presentation.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.enums.PresentationAnchorMode;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationIterationIntentType;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
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
                  "targetElementType": "TITLE",
                  "pageIndex": 1,
                  "replacementText": "7878",
                  "operations": [
                    {
                      "actionType": "REPLACE_SLIDE_TITLE",
                      "targetElementType": "TITLE",
                      "pageIndex": 1,
                      "replacementText": "7878"
                    }
                  ],
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
        assertThat(intent.getOperations()).hasSize(1);
        assertThat(intent.getOperations().get(0).getPageIndex()).isEqualTo(1);
    }

    @Test
    void resolvesMultiSlideOperations() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "operations": [
                    {
                      "actionType": "REPLACE_SLIDE_TITLE",
                      "targetElementType": "TITLE",
                      "pageIndex": 1,
                      "replacementText": "项目背景"
                    },
                    {
                      "actionType": "REPLACE_SLIDE_BODY",
                      "targetElementType": "BODY",
                      "pageIndex": 2,
                      "replacementText": "风险、排期、预算"
                    }
                  ],
                  "clarificationNeeded": false
                }
                """);
        PresentationEditIntentResolver resolver = new PresentationEditIntentResolver(chatModel, new ObjectMapper());

        PresentationEditIntent intent = resolver.resolve("把第一页标题改成项目背景，第二页正文改成风险、排期、预算");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getOperations()).hasSize(2);
        assertThat(intent.getOperations())
                .extracting(operation -> operation.getActionType().name())
                .containsExactly("REPLACE_SLIDE_TITLE", "REPLACE_SLIDE_BODY");
    }

    @Test
    void resolvesInsertSlideInstruction() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "INSERT",
                  "operations": [
                    {
                      "actionType": "INSERT_SLIDE",
                      "insertAfterPageIndex": 2,
                      "slideTitle": "风险应对",
                      "slideBody": "预算、排期、依赖"
                    }
                  ],
                  "clarificationNeeded": false
                }
                """);
        PresentationEditIntentResolver resolver = new PresentationEditIntentResolver(chatModel, new ObjectMapper());

        PresentationEditIntent intent = resolver.resolve("在第2页后插入一页，标题为风险应对，正文为预算、排期、依赖");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getActionType()).isEqualTo(PresentationEditActionType.INSERT_SLIDE);
        assertThat(intent.getInsertAfterPageIndex()).isEqualTo(2);
        assertThat(intent.getSlideTitle()).isEqualTo("风险应对");
        assertThat(intent.getSlideBody()).isEqualTo("预算、排期、依赖");
    }

    @Test
    void treatsInsertPageIndexAsInsertAfterPageIndexForNaturalAfterInstruction() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "INSERT",
                  "operations": [
                    {
                      "actionType": "INSERT_SLIDE",
                      "pageIndex": 2,
                      "slideTitle": "多端协作闭环",
                      "slideBody": "IM 发起、Planner 执行、文档沉淀、PPT 交付"
                    }
                  ],
                  "clarificationNeeded": false
                }
                """);
        PresentationEditIntentResolver resolver = new PresentationEditIntentResolver(chatModel, new ObjectMapper());

        PresentationEditIntent intent = resolver.resolve("在第2页后插入一页，标题为多端协作闭环，正文为 IM 发起、Planner 执行、文档沉淀、PPT 交付");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getOperations().get(0).getInsertAfterPageIndex()).isEqualTo(2);
        assertThat(intent.getInsertAfterPageIndex()).isEqualTo(2);
    }

    @Test
    void resolvesDeleteSlideInstruction() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "DELETE",
                  "operations": [
                    {
                      "actionType": "DELETE_SLIDE",
                      "pageIndex": 3
                    }
                  ],
                  "clarificationNeeded": false
                }
                """);
        PresentationEditIntentResolver resolver = new PresentationEditIntentResolver(chatModel, new ObjectMapper());

        PresentationEditIntent intent = resolver.resolve("删除第3页");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getActionType()).isEqualTo(PresentationEditActionType.DELETE_SLIDE);
        assertThat(intent.getPageIndex()).isEqualTo(3);
    }

    @Test
    void resolvesMoveSlideInstruction() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "operations": [
                    {
                      "actionType": "MOVE_SLIDE",
                      "pageIndex": 4,
                      "insertAfterPageIndex": 2
                    }
                  ],
                  "clarificationNeeded": false
                }
                """);
        PresentationEditIntentResolver resolver = new PresentationEditIntentResolver(chatModel, new ObjectMapper());

        PresentationEditIntent intent = resolver.resolve("把第4页移到第2页后");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getActionType()).isEqualTo(PresentationEditActionType.MOVE_SLIDE);
        assertThat(intent.getPageIndex()).isEqualTo(4);
        assertThat(intent.getInsertAfterPageIndex()).isEqualTo(2);
    }

    @Test
    void resolvesMoveSlideToEndInstructionFromStructuredTarget() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "operations": [
                    {
                      "actionType": "MOVE_SLIDE",
                      "pageIndex": 2,
                      "insertAfterPageIndex": -1
                    }
                  ],
                  "clarificationNeeded": false
                }
                """);
        PresentationEditIntentResolver resolver = new PresentationEditIntentResolver(chatModel, new ObjectMapper());

        PresentationEditIntent intent = resolver.resolve("把第2页移到最后");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getActionType()).isEqualTo(PresentationEditActionType.MOVE_SLIDE);
        assertThat(intent.getPageIndex()).isEqualTo(2);
        assertThat(intent.getInsertAfterPageIndex()).isEqualTo(-1);
    }

    @Test
    void insertWithoutContentFallsBackToClarification() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "INSERT",
                  "operations": [
                    {
                      "actionType": "INSERT_SLIDE",
                      "insertAfterPageIndex": 2
                    }
                  ],
                  "clarificationNeeded": false
                }
                """);
        PresentationEditIntentResolver resolver = new PresentationEditIntentResolver(chatModel, new ObjectMapper());

        PresentationEditIntent intent = resolver.resolve("插入一页");

        assertThat(intent.isClarificationNeeded()).isTrue();
    }

    @Test
    void moveWithoutTargetFallsBackToClarification() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "operations": [
                    {
                      "actionType": "MOVE_SLIDE",
                      "pageIndex": 4
                    }
                  ],
                  "clarificationNeeded": false
                }
                """);
        PresentationEditIntentResolver resolver = new PresentationEditIntentResolver(chatModel, new ObjectMapper());

        PresentationEditIntent intent = resolver.resolve("移动第4页");

        assertThat(intent.isClarificationNeeded()).isTrue();
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

    @Test
    void resolvesQuotedBodyAnchorInstruction() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "operations": [
                    {
                      "actionType": "EXPAND_ELEMENT",
                      "targetElementType": "BODY",
                      "anchorMode": "BY_QUOTED_TEXT",
                      "pageIndex": 1,
                      "quotedText": "文旅融合创新",
                      "contentInstruction": "写详细一些",
                      "replacementText": "文旅融合创新，消费场景丰富多元，带动区域体验升级"
                    }
                  ],
                  "clarificationNeeded": false
                }
                """);
        PresentationEditIntentResolver resolver = new PresentationEditIntentResolver(chatModel, new ObjectMapper());

        PresentationEditIntent intent = resolver.resolve("第一页这段“文旅融合创新”写详细一些");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getOperations()).hasSize(1);
        assertThat(intent.getOperations().get(0).getAnchorMode()).isEqualTo(PresentationAnchorMode.BY_QUOTED_TEXT);
        assertThat(intent.getOperations().get(0).getTargetElementType()).isEqualTo(PresentationTargetElementType.BODY);
        assertThat(intent.getOperations().get(0).getQuotedText()).isEqualTo("文旅融合创新");
    }

    @Test
    void resolvesImageReplacementInstruction() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "operations": [
                    {
                      "actionType": "REPLACE_IMAGE",
                      "targetElementType": "IMAGE",
                      "anchorMode": "BY_ELEMENT_ROLE",
                      "pageIndex": 2,
                      "elementRole": "right-image",
                      "replacementText": "门店实景图"
                    }
                  ],
                  "clarificationNeeded": false
                }
                """);
        PresentationEditIntentResolver resolver = new PresentationEditIntentResolver(chatModel, new ObjectMapper());

        PresentationEditIntent intent = resolver.resolve("把第2页右侧图片换成门店实景图");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getOperations()).hasSize(1);
        assertThat(intent.getOperations().get(0).getActionType()).isEqualTo(PresentationEditActionType.REPLACE_IMAGE);
        assertThat(intent.getOperations().get(0).getTargetElementType()).isEqualTo(PresentationTargetElementType.IMAGE);
        assertThat(intent.getOperations().get(0).getAnchorMode()).isEqualTo(PresentationAnchorMode.BY_ELEMENT_ROLE);
        assertThat(intent.getOperations().get(0).getElementRole()).isEqualTo("right-image");
    }
}
