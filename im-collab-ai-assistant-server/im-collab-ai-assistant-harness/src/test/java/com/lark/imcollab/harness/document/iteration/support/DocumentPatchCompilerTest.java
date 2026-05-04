package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentEditStrategy;
import com.lark.imcollab.common.model.entity.DocumentBlockAnchor;
import com.lark.imcollab.common.model.entity.DocumentSectionAnchor;
import com.lark.imcollab.common.model.entity.DocumentStructureNode;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.entity.DocumentTextAnchor;
import com.lark.imcollab.common.model.entity.ExpectedDocumentState;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.enums.DocumentAnchorType;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import com.lark.imcollab.common.model.enums.DocumentStrategyType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentPatchCompilerTest {

    @Test
    void insertBeforeSectionUsesExplicitBlockReplaceMatrix() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("## 前言\n\n这是新增章节");
        DocumentPatchCompiler compiler = new DocumentPatchCompiler(chatModel);

        DocumentEditPlan plan = compiler.compile(
                "task-1",
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.INSERT)
                        .semanticAction(DocumentSemanticActionType.INSERT_SECTION_BEFORE_SECTION)
                        .userInstruction("在项目背景前新增前言")
                        .build(),
                snapshot(),
                ResolvedDocumentAnchor.builder()
                        .anchorType(DocumentAnchorType.SECTION)
                        .sectionAnchor(DocumentSectionAnchor.builder()
                                .headingBlockId("heading-block")
                                .headingText("一、项目背景")
                                .headingLevel(2)
                                .allBlockIds(List.of("heading-block", "body-1"))
                                .bodyBlockIds(List.of("body-1"))
                                .build())
                        .preview("一、项目背景")
                        .build(),
                strategy(DocumentStrategyType.CONTROLLED_BEFORE_SECTION_INSERT, DocumentExpectedStateType.EXPECT_NEW_SECTION_BEFORE_TARGET_SECTION)
        );

        assertThat(plan.getToolCommandType()).isEqualTo(DocumentPatchOperationType.BLOCK_REPLACE);
        assertThat(plan.getPatchOperations()).singleElement().satisfies(operation -> {
            assertThat(operation.getOperationType()).isEqualTo(DocumentPatchOperationType.BLOCK_REPLACE);
            assertThat(operation.getBlockId()).isEqualTo("heading-block");
            assertThat(operation.getNewContent()).startsWith("## 前言");
            assertThat(operation.getNewContent()).contains("一、项目背景");
        });
        assertThat(plan.getStrategyType()).isEqualTo(DocumentStrategyType.CONTROLLED_BEFORE_SECTION_INSERT);
    }

    @Test
    void rewriteSectionBodyCompilesInsertThenDeleteOldBody() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                ## 一、项目背景

                ### 1.1 新正文

                管理层版本内容
                """);
        DocumentPatchCompiler compiler = new DocumentPatchCompiler(chatModel);

        DocumentEditPlan plan = compiler.compile(
                "task-2",
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.UPDATE_STYLE)
                        .semanticAction(DocumentSemanticActionType.REWRITE_SECTION_BODY)
                        .userInstruction("改成管理层风格")
                        .build(),
                snapshot(),
                ResolvedDocumentAnchor.builder()
                        .anchorType(DocumentAnchorType.SECTION)
                        .sectionAnchor(DocumentSectionAnchor.builder()
                                .headingBlockId("heading-block")
                                .headingText("一、项目背景")
                                .headingLevel(2)
                                .allBlockIds(List.of("heading-block", "body-1", "body-2"))
                                .bodyBlockIds(List.of("body-1", "body-2"))
                                .build())
                        .preview("一、项目背景")
                        .build(),
                strategy(DocumentStrategyType.BLOCK_REPLACE, DocumentExpectedStateType.EXPECT_TEXT_REPLACED)
        );

        assertThat(plan.getGeneratedContent()).startsWith("### 1.1 新正文");
        assertThat(plan.getPatchOperations()).hasSize(2);
        assertThat(plan.getPatchOperations().get(0).getOperationType()).isEqualTo(DocumentPatchOperationType.BLOCK_INSERT_AFTER);
        assertThat(plan.getPatchOperations().get(1).getOperationType()).isEqualTo(DocumentPatchOperationType.BLOCK_DELETE);
        assertThat(plan.getPatchOperations().get(1).getBlockId()).isEqualTo("body-1,body-2");
    }

    @Test
    void inlineRewriteRequiresUniqueTextMatch() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("新的说法");
        DocumentPatchCompiler compiler = new DocumentPatchCompiler(chatModel);

        DocumentEditPlan plan = compiler.compile(
                "task-3",
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                        .semanticAction(DocumentSemanticActionType.REWRITE_INLINE_TEXT)
                        .userInstruction("把这里改清楚")
                        .build(),
                snapshot(),
                ResolvedDocumentAnchor.builder()
                        .anchorType(DocumentAnchorType.TEXT)
                        .textAnchor(DocumentTextAnchor.builder()
                                .matchedText("旧内容")
                                .matchCount(2)
                                .build())
                        .preview("旧内容")
                        .build(),
                strategy(DocumentStrategyType.TEXT_REPLACE, DocumentExpectedStateType.EXPECT_TEXT_REPLACED)
        );

        assertThat(plan.getToolCommandType()).isEqualTo(DocumentPatchOperationType.STR_REPLACE);
        assertThat(plan.isRequiresApproval()).isTrue();
    }

    @Test
    void appendRejectsHeadingOnlySection() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("## 附录");
        DocumentPatchCompiler compiler = new DocumentPatchCompiler(chatModel);

        assertThatThrownBy(() -> compiler.compile(
                "task-4",
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.INSERT)
                        .semanticAction(DocumentSemanticActionType.APPEND_SECTION_TO_DOCUMENT_END)
                        .userInstruction("在文末新增附录")
                        .build(),
                snapshot(),
                ResolvedDocumentAnchor.builder()
                        .anchorType(DocumentAnchorType.DOCUMENT_TAIL)
                        .preview("DOC_TAIL")
                        .build(),
                strategy(DocumentStrategyType.APPEND, DocumentExpectedStateType.EXPECT_CONTENT_APPENDED)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有正文");
    }

    @Test
    void deleteMetadataUsesHeadDeletePlanAndPreservesPreviewText() {
        ChatModel chatModel = mock(ChatModel.class);
        DocumentPatchCompiler compiler = new DocumentPatchCompiler(chatModel);

        DocumentEditPlan plan = compiler.compile(
                "task-5",
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.DELETE)
                        .semanticAction(DocumentSemanticActionType.DELETE_METADATA_AT_DOCUMENT_HEAD)
                        .userInstruction("删除开头的作者信息")
                        .build(),
                metadataSnapshot(),
                ResolvedDocumentAnchor.builder()
                        .anchorType(DocumentAnchorType.BLOCK)
                        .blockAnchor(DocumentBlockAnchor.builder()
                                .blockId("meta-1")
                                .blockType("p")
                                .plainText("作者：张三")
                                .build())
                        .preview("作者：张三")
                        .build(),
                strategy(DocumentStrategyType.CONTROLLED_HEAD_DELETE, DocumentExpectedStateType.EXPECT_BLOCK_REMOVED)
        );

        assertThat(plan.getToolCommandType()).isEqualTo(DocumentPatchOperationType.BLOCK_DELETE);
        assertThat(plan.getResolvedAnchor().getBlockAnchor().getPlainText()).isEqualTo("作者：张三");
        assertThat(plan.getPatchOperations()).singleElement().satisfies(operation -> {
            assertThat(operation.getBlockId()).isEqualTo("meta-1");
            assertThat(operation.getJustification()).contains("metadata");
        });
    }

    @Test
    void rewriteMetadataUsesBlockReplaceInsteadOfStringReplace() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("作者：李四");
        DocumentPatchCompiler compiler = new DocumentPatchCompiler(chatModel);

        DocumentEditPlan plan = compiler.compile(
                "task-5b",
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                        .semanticAction(DocumentSemanticActionType.REWRITE_METADATA_AT_DOCUMENT_HEAD)
                        .userInstruction("修改文章开头的作者信息为李四")
                        .build(),
                metadataSnapshot(),
                ResolvedDocumentAnchor.builder()
                        .anchorType(DocumentAnchorType.BLOCK)
                        .blockAnchor(DocumentBlockAnchor.builder()
                                .blockId("meta-1")
                                .blockType("p")
                                .plainText("作者：张三")
                                .build())
                        .preview("作者：张三")
                        .build(),
                strategy(DocumentStrategyType.CONTROLLED_HEAD_REWRITE, DocumentExpectedStateType.EXPECT_TEXT_REPLACED)
        );

        assertThat(plan.getToolCommandType()).isEqualTo(DocumentPatchOperationType.BLOCK_REPLACE);
        assertThat(plan.getResolvedAnchor().getBlockAnchor().getPlainText()).isEqualTo("作者：张三");
        assertThat(plan.getGeneratedContent()).isEqualTo("作者：李四");
        assertThat(plan.getPatchOperations()).singleElement().satisfies(operation -> {
            assertThat(operation.getOperationType()).isEqualTo(DocumentPatchOperationType.BLOCK_REPLACE);
            assertThat(operation.getBlockId()).isEqualTo("meta-1");
        });
    }

    @Test
    void deleteBlockRejectsProtectedHeadingBlock() {
        ChatModel chatModel = mock(ChatModel.class);
        DocumentPatchCompiler compiler = new DocumentPatchCompiler(chatModel);

        assertThatThrownBy(() -> compiler.compile(
                "task-6",
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.DELETE)
                        .semanticAction(DocumentSemanticActionType.DELETE_BLOCK)
                        .userInstruction("删除项目背景")
                        .build(),
                metadataSnapshot(),
                ResolvedDocumentAnchor.builder()
                        .anchorType(DocumentAnchorType.BLOCK)
                        .blockAnchor(DocumentBlockAnchor.builder()
                                .blockId("heading-1")
                                .blockType("heading")
                                .plainText("一、项目背景")
                                .build())
                        .preview("一、项目背景")
                        .build(),
                strategy(DocumentStrategyType.BLOCK_DELETE, DocumentExpectedStateType.EXPECT_BLOCK_REMOVED)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("受保护结构");
    }

    private DocumentStructureSnapshot snapshot() {
        return DocumentStructureSnapshot.builder()
                .docId("doc-1")
                .rawFullMarkdown("""
                        ## 一、项目背景

                        旧正文第一段

                        旧正文第二段
                        """)
                .build();
    }

    private DocumentStructureSnapshot metadataSnapshot() {
        Map<String, DocumentStructureNode> blockIndex = new LinkedHashMap<>();
        blockIndex.put("meta-1", DocumentStructureNode.builder().blockId("meta-1").blockType("p").plainText("作者：张三").build());
        blockIndex.put("heading-1", DocumentStructureNode.builder().blockId("heading-1").blockType("heading").headingLevel(2).titleText("一、项目背景").plainText("一、项目背景").build());
        blockIndex.put("body-1", DocumentStructureNode.builder().blockId("body-1").blockType("p").plainText("正文内容").build());
        return DocumentStructureSnapshot.builder()
                .docId("doc-2")
                .blockIndex(blockIndex)
                .headingIndex(Map.of("heading-1", blockIndex.get("heading-1")))
                .topLevelSequence(List.of("heading-1"))
                .rawOutlineXml("<h2 id=\"heading-1\">一、项目背景</h2>")
                .rawFullXml("<doc><p id=\"meta-1\">作者：张三</p><h2 id=\"heading-1\">一、项目背景</h2><p id=\"body-1\">正文内容</p></doc>")
                .rawFullMarkdown("作者：张三\n\n## 一、项目背景\n\n正文内容")
                .build();
    }

    private DocumentEditStrategy strategy(DocumentStrategyType type, DocumentExpectedStateType expectedStateType) {
        return DocumentEditStrategy.builder()
                .strategyType(type)
                .anchorType(DocumentAnchorType.SECTION)
                .patchFamily(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                .expectedState(ExpectedDocumentState.builder()
                        .stateType(expectedStateType)
                        .attributes(Map.of())
                        .build())
                .requiresApproval(false)
                .riskLevel(DocumentRiskLevel.MEDIUM)
                .build();
    }
}
