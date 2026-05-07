package com.lark.imcollab.harness.document.iteration.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.enums.DocumentAnchorKind;
import com.lark.imcollab.common.model.enums.DocumentAnchorMatchMode;
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

    @Test
    void fencedJsonWithoutAnchorIsRejectedInsteadOfBeingPatchedLocally() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                ```json
                {
                  "intentType": "INSERT",
                  "semanticAction": "INSERT_BLOCK_AFTER_ANCHOR",
                  "clarificationNeeded": false
                }
                ```
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("在1.1 游客规模与收入中新增一段文字：介绍东方明珠");

        assertThat(intent.isClarificationNeeded()).isTrue();
        assertThat(intent.getClarificationHint()).contains("缺少明确锚点");
    }

    @Test
    void deleteInstructionWithoutStableAnchorIsRejectedInsteadOfDangerousFallback() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "DELETE",
                  "semanticAction": "DELETE_INLINE_TEXT",
                  "clarificationNeeded": false
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("删除这一段");

        assertThat(intent.isClarificationNeeded()).isTrue();
        assertThat(intent.getClarificationHint()).contains("缺少明确锚点");
    }

    @Test
    void byBlockIdAnchorIsAcceptedWhenStructuredFieldIsPresent() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "semanticAction": "REWRITE_SECTION_BODY",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "SECTION",
                    "matchMode": "BY_BLOCK_ID",
                    "blockId": "heading-1"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("改写这个 section");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getBlockId()).isEqualTo("heading-1");
    }

    @Test
    void byBlockIdAnchorWithoutBlockIdIsRejected() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "semanticAction": "REWRITE_SECTION_BODY",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "SECTION",
                    "matchMode": "BY_BLOCK_ID"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("改写这个 section");

        assertThat(intent.isClarificationNeeded()).isTrue();
        assertThat(intent.getClarificationHint()).contains("锚点描述不完整");
    }

    @Test
    void sectionLikeInstructionWithSyntheticQuotedTextIsNormalizedToSectionRewrite() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "semanticAction": "REWRITE_SINGLE_BLOCK",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "BLOCK",
                    "matchMode": "BY_QUOTED_TEXT",
                    "quotedText": "总体复苏态势中的数据"
                  },
                  "rewriteSpec": {
                    "targetContent": "总体复苏态势中的数据",
                    "styleOnly": false,
                    "newContent": "2026年的数据"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("1.1 总体复苏态势中的数据太久了，换成2026年的");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.REWRITE_SECTION_BODY);
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getAnchorKind()).isEqualTo(DocumentAnchorKind.SECTION);
        assertThat(intent.getAnchorSpec().getMatchMode()).isEqualTo(DocumentAnchorMatchMode.BY_HEADING_NUMBER);
        assertThat(intent.getAnchorSpec().getHeadingNumber()).isEqualTo("1.1");
    }

    @Test
    void quotedLiteralTextRemainsValidWhenUserProvidesDirectCitation() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "semanticAction": "REWRITE_INLINE_TEXT",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "TEXT",
                    "matchMode": "BY_QUOTED_TEXT",
                    "quotedText": "总体复苏态势中的数据"
                  },
                  "rewriteSpec": {
                    "targetContent": "总体复苏态势中的数据",
                    "styleOnly": false,
                    "newContent": "2026年的数据"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("把“总体复苏态势中的数据”替换成“2026年的数据”");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getQuotedText()).isEqualTo("总体复苏态势中的数据");
        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.REWRITE_INLINE_TEXT);
    }

    @Test
    void sectionNumberInstructionNormalizesWeakModelOutputToSectionRewrite() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "semanticAction": "REWRITE_SINGLE_BLOCK",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "BLOCK",
                    "matchMode": "BY_QUOTED_TEXT",
                    "quotedText": "客源市场结构的数据"
                  },
                  "rewriteSpec": {
                    "targetContent": "客源市场结构的数据",
                    "styleOnly": false,
                    "newContent": "补充具体数据说明"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("1.3 客源市场结构给出具体的数据说明");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.REWRITE_SECTION_BODY);
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getAnchorKind()).isEqualTo(DocumentAnchorKind.SECTION);
        assertThat(intent.getAnchorSpec().getMatchMode()).isEqualTo(DocumentAnchorMatchMode.BY_HEADING_NUMBER);
        assertThat(intent.getAnchorSpec().getHeadingNumber()).isEqualTo("1.3");
    }

    @Test
    void chineseCompositeSectionReferenceNormalizesToOutlinePath() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "semanticAction": "REWRITE_SINGLE_BLOCK",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "BLOCK",
                    "matchMode": "BY_QUOTED_TEXT",
                    "quotedText": "这一节内容"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("把第3章第2节展开说明");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.REWRITE_SECTION_BODY);
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getAnchorKind()).isEqualTo(DocumentAnchorKind.SECTION);
        assertThat(intent.getAnchorSpec().getMatchMode()).isEqualTo(DocumentAnchorMatchMode.BY_STRUCTURAL_ORDINAL);
        assertThat(intent.getAnchorSpec().getParentHeadingNumber()).isEqualTo("3");
        assertThat(intent.getAnchorSpec().getStructuralOrdinal()).isEqualTo(2);
        assertThat(intent.getAnchorSpec().getStructuralOrdinalScope()).isEqualTo("CHILD_OF_HEADING_NUMBER:3");
    }

    @Test
    void chineseSingleSectionReferenceUsesStructuralOrdinal() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "semanticAction": "REWRITE_SINGLE_BLOCK",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "BLOCK",
                    "matchMode": "BY_QUOTED_TEXT",
                    "quotedText": "这一章内容"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("补充第3章的数据说明");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.REWRITE_SECTION_BODY);
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getAnchorKind()).isEqualTo(DocumentAnchorKind.SECTION);
        assertThat(intent.getAnchorSpec().getMatchMode()).isEqualTo(DocumentAnchorMatchMode.BY_STRUCTURAL_ORDINAL);
        assertThat(intent.getAnchorSpec().getStructuralOrdinal()).isEqualTo(3);
        assertThat(intent.getAnchorSpec().getStructuralOrdinalScope()).isEqualTo("TOP_LEVEL_SECTION");
    }

    @Test
    void decimalThreeLevelReferenceNormalizesToOutlinePath() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "UPDATE_CONTENT",
                  "semanticAction": "REWRITE_SINGLE_BLOCK",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "BLOCK",
                    "matchMode": "BY_QUOTED_TEXT",
                    "quotedText": "对应小节内容"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("补充1.2.3的数据说明");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.REWRITE_SECTION_BODY);
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getAnchorKind()).isEqualTo(DocumentAnchorKind.SECTION);
        assertThat(intent.getAnchorSpec().getMatchMode()).isEqualTo(DocumentAnchorMatchMode.BY_OUTLINE_PATH_NUMBERS);
        assertThat(intent.getAnchorSpec().getOutlinePathNumbers()).isEqualTo("1.2.3");
    }

    @Test
    void insertAfterSectionInstructionNormalizesToHeadingAnchorInsteadOfOrdinal() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "INSERT",
                  "semanticAction": "INSERT_SECTION_BEFORE_SECTION",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "SECTION",
                    "matchMode": "BY_STRUCTURAL_ORDINAL",
                    "structuralOrdinal": 3,
                    "structuralOrdinalScope": "TOP_LEVEL_SECTION"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("在1.3 客源市场结构 后插入1.4 游客偏好分析");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getIntentType()).isEqualTo(com.lark.imcollab.common.model.enums.DocumentIterationIntentType.INSERT);
        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.INSERT_BLOCK_AFTER_ANCHOR);
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getAnchorKind()).isEqualTo(DocumentAnchorKind.SECTION);
        assertThat(intent.getAnchorSpec().getMatchMode()).isEqualTo(DocumentAnchorMatchMode.BY_HEADING_NUMBER);
        assertThat(intent.getAnchorSpec().getHeadingNumber()).isEqualTo("1.3");
    }

    @Test
    void insertAfterInstructionUsesAnchorSectionInsteadOfInsertedSectionNumber() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "INSERT",
                  "semanticAction": "INSERT_SECTION_BEFORE_SECTION",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "SECTION",
                    "matchMode": "BY_HEADING_NUMBER",
                    "headingNumber": "1.2"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("1.1 后插入一章1.2");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.INSERT_BLOCK_AFTER_ANCHOR);
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getMatchMode()).isEqualTo(DocumentAnchorMatchMode.BY_HEADING_NUMBER);
        assertThat(intent.getAnchorSpec().getHeadingNumber()).isEqualTo("1.1");
    }

    @Test
    void insertAfterSectionInstructionKeepsSectionAnchorForGeneratedSectionInsert() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "INSERT",
                  "semanticAction": "INSERT_BLOCK_AFTER_ANCHOR",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "SECTION",
                    "matchMode": "BY_HEADING_NUMBER",
                    "headingNumber": "2.3"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("2.3 专业建设与平台支撑后插入一段2.5 优势学科介绍");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.INSERT_BLOCK_AFTER_ANCHOR);
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getAnchorKind()).isEqualTo(DocumentAnchorKind.SECTION);
        assertThat(intent.getAnchorSpec().getMatchMode()).isEqualTo(DocumentAnchorMatchMode.BY_HEADING_NUMBER);
        assertThat(intent.getAnchorSpec().getHeadingNumber()).isEqualTo("2.3");
        assertThat(intent.getAnchorSpec().getRelativePosition()).isEqualTo("AFTER");
    }

    @Test
    void insertBeforeSectionInstructionStillUsesBeforeSectionAction() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "INSERT",
                  "semanticAction": "INSERT_SECTION_BEFORE_SECTION",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "SECTION",
                    "matchMode": "BY_HEADING_NUMBER",
                    "headingNumber": "2.3"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("2.3 专业建设与平台支撑前插入一段2.4 优势学科介绍");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.INSERT_SECTION_BEFORE_SECTION);
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getAnchorKind()).isEqualTo(DocumentAnchorKind.SECTION);
        assertThat(intent.getAnchorSpec().getMatchMode()).isEqualTo(DocumentAnchorMatchMode.BY_HEADING_NUMBER);
        assertThat(intent.getAnchorSpec().getHeadingNumber()).isEqualTo("2.3");
        assertThat(intent.getAnchorSpec().getRelativePosition()).isEqualTo("BEFORE");
    }

    @Test
    void sentenceAppendInsideSectionNormalizesInlineInsertToSectionAfterAnchor() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "INSERT",
                  "semanticAction": "INSERT_INLINE_TEXT",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "SECTION",
                    "matchMode": "BY_HEADING_NUMBER",
                    "headingNumber": "2.2"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("把这份文档在 2.2 验证结论末尾补充一句：IM-DOC-ONCE-UNIQUE-CHECK-001。");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getIntentType()).isEqualTo(com.lark.imcollab.common.model.enums.DocumentIterationIntentType.INSERT);
        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.INSERT_BLOCK_AFTER_ANCHOR);
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getAnchorKind()).isEqualTo(DocumentAnchorKind.SECTION);
        assertThat(intent.getAnchorSpec().getMatchMode()).isEqualTo(DocumentAnchorMatchMode.BY_HEADING_NUMBER);
        assertThat(intent.getAnchorSpec().getHeadingNumber()).isEqualTo("2.2");
    }

    @Test
    void deleteWholeSectionInstructionNormalizesOrdinalAnchorToHeadingTitle() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                {
                  "intentType": "DELETE",
                  "semanticAction": "DELETE_WHOLE_SECTION",
                  "clarificationNeeded": false,
                  "anchorSpec": {
                    "anchorKind": "SECTION",
                    "matchMode": "BY_STRUCTURAL_ORDINAL",
                    "structuralOrdinal": 3,
                    "structuralOrdinalScope": "TOP_LEVEL_SECTION"
                  }
                }
                """);
        DocumentEditIntentResolver resolver = new DocumentEditIntentResolver(chatModel, new ObjectMapper());

        DocumentEditIntent intent = resolver.resolve("删1.3 客源市场结构");

        assertThat(intent.isClarificationNeeded()).isFalse();
        assertThat(intent.getIntentType()).isEqualTo(com.lark.imcollab.common.model.enums.DocumentIterationIntentType.DELETE);
        assertThat(intent.getSemanticAction()).isEqualTo(DocumentSemanticActionType.DELETE_WHOLE_SECTION);
        assertThat(intent.getAnchorSpec()).isNotNull();
        assertThat(intent.getAnchorSpec().getAnchorKind()).isEqualTo(DocumentAnchorKind.SECTION);
        assertThat(intent.getAnchorSpec().getMatchMode()).isEqualTo(DocumentAnchorMatchMode.BY_HEADING_NUMBER);
        assertThat(intent.getAnchorSpec().getHeadingNumber()).isEqualTo("1.3");
    }
}
