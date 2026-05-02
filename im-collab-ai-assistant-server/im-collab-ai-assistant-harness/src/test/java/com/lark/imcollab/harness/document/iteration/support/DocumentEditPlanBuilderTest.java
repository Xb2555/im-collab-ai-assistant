package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.common.model.entity.DocumentTargetSelector;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentLocatorStrategy;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.common.model.enums.DocumentRelativePosition;
import com.lark.imcollab.common.model.enums.DocumentTargetType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentEditPlanBuilderTest {

    @Test
    void sectionRewriteStripsDuplicatedHeadingFromModelOutput() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                ## 一、项目背景与问题

                ### 1.1 新正文

                管理层版本内容
                """);
        DocumentEditPlanBuilder builder = new DocumentEditPlanBuilder(chatModel);
        DocumentTargetSelector selector = DocumentTargetSelector.builder()
                .targetType(DocumentTargetType.SECTION)
                .locatorStrategy(DocumentLocatorStrategy.BY_HEADING)
                .locatorValue("一、项目背景与问题")
                .matchedExcerpt("旧正文")
                .matchedBlockIds(List.of("heading-block", "body-block"))
                .build();

        DocumentEditPlan plan = builder.build("task-1", DocumentIterationIntentType.UPDATE_STYLE, selector, "改成管理层风格");

        assertThat(plan.getGeneratedContent()).startsWith("### 1.1 新正文");
        assertThat(plan.getGeneratedContent()).doesNotStartWith("## 一、项目背景与问题");
    }

    @Test
    void insertBeforeHeadingUsesStringReplaceToPreserveHeadingHierarchy() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("## 前言\n\n这是新增章节");
        DocumentEditPlanBuilder builder = new DocumentEditPlanBuilder(chatModel);
        DocumentTargetSelector selector = DocumentTargetSelector.builder()
                .targetType(DocumentTargetType.TITLE)
                .locatorStrategy(DocumentLocatorStrategy.BY_HEADING)
                .relativePosition(DocumentRelativePosition.BEFORE)
                .locatorValue("一、项目背景")
                .matchedExcerpt("## 一、项目背景")
                .matchedBlockIds(List.of("heading-block"))
                .build();

        DocumentEditPlan plan = builder.build("task-1", DocumentIterationIntentType.INSERT, selector, "在项目背景前新增前言");
        DocumentPatchOperation operation = plan.getPatchOperations().get(0);

        assertThat(plan.getToolCommandType()).isEqualTo(DocumentPatchOperationType.STR_REPLACE);
        assertThat(operation.getOperationType()).isEqualTo(DocumentPatchOperationType.STR_REPLACE);
        assertThat(operation.getBlockId()).isNull();
        assertThat(operation.getOldText()).isEqualTo("一、项目背景");
        assertThat(operation.getNewContent()).startsWith("前言");
        assertThat(operation.getNewContent()).doesNotStartWith("## 前言");
        assertThat(operation.getNewContent()).contains("## 一、项目背景");
    }

    @Test
    void insertBeforeHeadingNormalizesHeadingLevelToAnchor() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("### 前言\n\n这是新增章节");
        DocumentEditPlanBuilder builder = new DocumentEditPlanBuilder(chatModel);
        DocumentTargetSelector selector = DocumentTargetSelector.builder()
                .targetType(DocumentTargetType.TITLE)
                .locatorStrategy(DocumentLocatorStrategy.DOC_START)
                .relativePosition(DocumentRelativePosition.BEFORE)
                .matchedExcerpt("## 一、项目背景")
                .matchedBlockIds(List.of("heading-block"))
                .build();

        DocumentEditPlan plan = builder.build("task-1", DocumentIterationIntentType.INSERT, selector, "在文档开头新增前言");

        assertThat(plan.getGeneratedContent()).startsWith("## 前言");
    }

    @Test
    void insertAtDocEndUsesAppend() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("## 附录\n\n补充内容");
        DocumentEditPlanBuilder builder = new DocumentEditPlanBuilder(chatModel);
        DocumentTargetSelector selector = DocumentTargetSelector.builder()
                .targetType(DocumentTargetType.BLOCK)
                .locatorStrategy(DocumentLocatorStrategy.DOC_END)
                .relativePosition(DocumentRelativePosition.AFTER)
                .matchedBlockIds(List.of("last-block"))
                .matchedExcerpt("")
                .build();

        DocumentEditPlan plan = builder.build("task-1", DocumentIterationIntentType.INSERT, selector, "在文档末尾新增附录");

        assertThat(plan.getToolCommandType()).isEqualTo(DocumentPatchOperationType.APPEND);
        assertThat(plan.getPatchOperations()).singleElement().satisfies(operation -> {
            assertThat(operation.getOperationType()).isEqualTo(DocumentPatchOperationType.APPEND);
            assertThat(operation.getNewContent()).isEqualTo("## 附录\n\n补充内容");
        });
    }

    @Test
    void insertChapterRejectsHeadingOnlyContent() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("## 一、项目背景");
        DocumentEditPlanBuilder builder = new DocumentEditPlanBuilder(chatModel);
        DocumentTargetSelector selector = DocumentTargetSelector.builder()
                .targetType(DocumentTargetType.TITLE)
                .locatorStrategy(DocumentLocatorStrategy.DOC_START)
                .relativePosition(DocumentRelativePosition.BEFORE)
                .matchedExcerpt("## 二、设计目标")
                .matchedBlockIds(List.of("heading-block"))
                .build();

        assertThatThrownBy(() -> builder.build("task-1", DocumentIterationIntentType.INSERT, selector, "在文档开头新增章节：一、项目背景"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有正文");
    }
}
