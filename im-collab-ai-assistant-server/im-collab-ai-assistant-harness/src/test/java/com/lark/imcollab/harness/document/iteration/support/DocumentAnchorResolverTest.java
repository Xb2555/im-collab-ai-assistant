package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.model.entity.DocumentAnchorSpec;
import com.lark.imcollab.common.model.entity.DocumentBlockAnchor;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.DocumentMediaAnchor;
import com.lark.imcollab.common.model.entity.DocumentStructureNode;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.entity.DocumentTextAnchor;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.enums.DocumentAnchorKind;
import com.lark.imcollab.common.model.enums.DocumentAnchorMatchMode;
import com.lark.imcollab.common.model.enums.DocumentAnchorType;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import com.lark.imcollab.common.model.enums.MediaAssetType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentAnchorResolverTest {

    @Test
    void deleteMetadataAtHeadResolvesAuthorBlockInsteadOfFirstHeading() {
        DocumentAnchorResolver resolver = new DocumentAnchorResolver(null);

        ResolvedDocumentAnchor anchor = resolver.resolve(
                Artifact.builder().externalUrl("https://example.com/docx/doc123").build(),
                snapshot(),
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.DELETE)
                        .semanticAction(DocumentSemanticActionType.DELETE_METADATA_AT_DOCUMENT_HEAD)
                        .userInstruction("删除开头的作者信息")
                        .anchorSpec(DocumentAnchorSpec.builder()
                                .anchorKind(DocumentAnchorKind.DOCUMENT_HEAD)
                                .matchMode(DocumentAnchorMatchMode.DOC_START)
                                .quotedText("作者：张三")
                                .build())
                        .build()
        );

        DocumentBlockAnchor blockAnchor = anchor.getBlockAnchor();
        assertThat(anchor.getPreview()).isEqualTo("作者：张三");
        assertThat(blockAnchor.getBlockId()).isEqualTo("meta-1");
        assertThat(blockAnchor.getPlainText()).isEqualTo("作者：张三");
    }

    @Test
    void duplicateQuotedTextResolvesToUnresolvedInsteadOfPickingFirstBlock() {
        DocumentAnchorResolver resolver = new DocumentAnchorResolver(null);

        ResolvedDocumentAnchor anchor = resolver.resolve(
                Artifact.builder().externalUrl("https://example.com/docx/doc123").build(),
                duplicateTextSnapshot(),
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                        .semanticAction(DocumentSemanticActionType.REWRITE_INLINE_TEXT)
                        .userInstruction("把这句话改掉")
                        .anchorSpec(DocumentAnchorSpec.builder()
                                .anchorKind(DocumentAnchorKind.TEXT)
                                .matchMode(DocumentAnchorMatchMode.BY_QUOTED_TEXT)
                                .quotedText("重复内容")
                                .build())
                        .build()
        );

        assertThat(anchor.getAnchorType()).isEqualTo(DocumentAnchorType.UNRESOLVED);
    }

    @Test
    void ambiguousHeadingTitleResolvesToUnresolvedInsteadOfFuzzyMatch() {
        DocumentAnchorResolver resolver = new DocumentAnchorResolver(null);

        ResolvedDocumentAnchor anchor = resolver.resolve(
                Artifact.builder().externalUrl("https://example.com/docx/doc123").build(),
                duplicateHeadingSnapshot(),
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.INSERT)
                        .semanticAction(DocumentSemanticActionType.INSERT_SECTION_BEFORE_SECTION)
                        .userInstruction("在项目背景前插入")
                        .anchorSpec(DocumentAnchorSpec.builder()
                                .anchorKind(DocumentAnchorKind.SECTION)
                                .matchMode(DocumentAnchorMatchMode.BY_HEADING_TITLE)
                                .headingTitle("项目背景")
                                .build())
                        .build()
        );

        assertThat(anchor.getAnchorType()).isEqualTo(DocumentAnchorType.UNRESOLVED);
    }

    @Test
    void duplicateMediaCaptionResolvesToUnresolvedInsteadOfPickingFirstMediaNode() {
        DocumentAnchorResolver resolver = new DocumentAnchorResolver(null);

        ResolvedDocumentAnchor anchor = resolver.resolve(
                Artifact.builder().externalUrl("https://example.com/docx/doc123").build(),
                duplicateMediaSnapshot(),
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                        .semanticAction(DocumentSemanticActionType.REPLACE_IMAGE)
                        .userInstruction("替换示意图")
                        .anchorSpec(DocumentAnchorSpec.builder()
                                .anchorKind(DocumentAnchorKind.MEDIA)
                                .matchMode(DocumentAnchorMatchMode.BY_MEDIA_CAPTION)
                                .mediaCaption("示意图")
                                .build())
                        .build()
        );

        assertThat(anchor.getAnchorType()).isEqualTo(DocumentAnchorType.UNRESOLVED);
    }

    @Test
    void byBlockIdSectionAnchorResolvesOnlyWhenBlockIsRealHeading() {
        DocumentAnchorResolver resolver = new DocumentAnchorResolver(null);

        ResolvedDocumentAnchor anchor = resolver.resolve(
                Artifact.builder().externalUrl("https://example.com/docx/doc123").build(),
                snapshot(),
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                        .semanticAction(DocumentSemanticActionType.REWRITE_SECTION_BODY)
                        .userInstruction("改写这个章节")
                        .anchorSpec(DocumentAnchorSpec.builder()
                                .anchorKind(DocumentAnchorKind.SECTION)
                                .matchMode(DocumentAnchorMatchMode.BY_BLOCK_ID)
                                .blockId("heading-1")
                                .build())
                        .build()
        );

        assertThat(anchor.getAnchorType()).isEqualTo(DocumentAnchorType.SECTION);
        assertThat(anchor.getSectionAnchor()).isNotNull();
        assertThat(anchor.getSectionAnchor().getHeadingBlockId()).isEqualTo("heading-1");
    }

    @Test
    void byBlockIdSectionAnchorRejectsNonHeadingBlock() {
        DocumentAnchorResolver resolver = new DocumentAnchorResolver(null);

        ResolvedDocumentAnchor anchor = resolver.resolve(
                Artifact.builder().externalUrl("https://example.com/docx/doc123").build(),
                snapshot(),
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                        .semanticAction(DocumentSemanticActionType.REWRITE_SECTION_BODY)
                        .userInstruction("改写这个章节")
                        .anchorSpec(DocumentAnchorSpec.builder()
                                .anchorKind(DocumentAnchorKind.SECTION)
                                .matchMode(DocumentAnchorMatchMode.BY_BLOCK_ID)
                                .blockId("body-1")
                                .build())
                        .build()
        );

        assertThat(anchor.getAnchorType()).isEqualTo(DocumentAnchorType.UNRESOLVED);
    }

    @Test
    void byBlockIdBlockAnchorResolvesTargetBlockWithoutQuotedTextFallback() {
        DocumentAnchorResolver resolver = new DocumentAnchorResolver(null);

        ResolvedDocumentAnchor anchor = resolver.resolve(
                Artifact.builder().externalUrl("https://example.com/docx/doc123").build(),
                snapshot(),
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                        .semanticAction(DocumentSemanticActionType.REWRITE_METADATA_AT_DOCUMENT_HEAD)
                        .userInstruction("改写开头元信息")
                        .anchorSpec(DocumentAnchorSpec.builder()
                                .anchorKind(DocumentAnchorKind.DOCUMENT_HEAD)
                                .matchMode(DocumentAnchorMatchMode.BY_BLOCK_ID)
                                .blockId("meta-1")
                                .build())
                        .build()
        );

        assertThat(anchor.getAnchorType()).isEqualTo(DocumentAnchorType.BLOCK);
        assertThat(anchor.getBlockAnchor()).isNotNull();
        assertThat(anchor.getBlockAnchor().getBlockId()).isEqualTo("meta-1");
    }

    @Test
    void byStructuralOrdinalSubSectionResolvesOrderedHeading() {
        DocumentAnchorResolver resolver = new DocumentAnchorResolver(null);

        ResolvedDocumentAnchor anchor = resolver.resolve(
                Artifact.builder().externalUrl("https://example.com/docx/doc123").build(),
                nestedHeadingSnapshot(),
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                        .semanticAction(DocumentSemanticActionType.REWRITE_SECTION_BODY)
                        .userInstruction("改写 1.3 小节")
                        .anchorSpec(DocumentAnchorSpec.builder()
                                .anchorKind(DocumentAnchorKind.SECTION)
                                .matchMode(DocumentAnchorMatchMode.BY_STRUCTURAL_ORDINAL)
                                .structuralOrdinal(3)
                                .structuralOrdinalScope("CHILD_OF_HEADING_NUMBER:1")
                                .build())
                        .build()
        );

        assertThat(anchor.getAnchorType()).isEqualTo(DocumentAnchorType.SECTION);
        assertThat(anchor.getSectionAnchor()).isNotNull();
        assertThat(anchor.getSectionAnchor().getHeadingBlockId()).isEqualTo("heading-1-3");
        assertThat(anchor.getSectionAnchor().getHeadingText()).isEqualTo("1.3 客源市场结构");
    }

    @Test
    void byHeadingNumberResolvesExactSection() {
        DocumentAnchorResolver resolver = new DocumentAnchorResolver(null);

        ResolvedDocumentAnchor anchor = resolver.resolve(
                Artifact.builder().externalUrl("https://example.com/docx/doc123").build(),
                nestedHeadingSnapshot(),
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.UPDATE_CONTENT)
                        .semanticAction(DocumentSemanticActionType.REWRITE_SECTION_BODY)
                        .userInstruction("改写 1.3 小节")
                        .anchorSpec(DocumentAnchorSpec.builder()
                                .anchorKind(DocumentAnchorKind.SECTION)
                                .matchMode(DocumentAnchorMatchMode.BY_HEADING_NUMBER)
                                .headingNumber("1.3")
                                .build())
                        .build()
        );

        assertThat(anchor.getAnchorType()).isEqualTo(DocumentAnchorType.SECTION);
        assertThat(anchor.getSectionAnchor()).isNotNull();
        assertThat(anchor.getSectionAnchor().getHeadingBlockId()).isEqualTo("heading-1-3");
        assertThat(anchor.getSectionAnchor().getHeadingNumber()).isEqualTo("1.3");
    }

    @Test
    void insertImageAfterAnchorResolvesSectionByTitleWithoutChineseListPrefix() {
        DocumentAnchorResolver resolver = new DocumentAnchorResolver(null);

        ResolvedDocumentAnchor anchor = resolver.resolve(
                Artifact.builder().externalUrl("https://example.com/docx/doc123").build(),
                mediaInsertSnapshot(),
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.INSERT_MEDIA)
                        .semanticAction(DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR)
                        .userInstruction("一、发展现状与总体态势后插入一张广州的图片")
                        .anchorSpec(DocumentAnchorSpec.builder()
                                .anchorKind(DocumentAnchorKind.SECTION)
                                .matchMode(DocumentAnchorMatchMode.BY_HEADING_TITLE)
                                .headingTitle("发展现状与总体态势")
                                .build())
                        .build()
        );

        assertThat(anchor.getAnchorType()).isEqualTo(DocumentAnchorType.BLOCK);
        assertThat(anchor.getSectionAnchor()).isNotNull();
        assertThat(anchor.getSectionAnchor().getHeadingText()).isEqualTo("一、发展现状与总体态势");
        assertThat(anchor.getInsertionBlockId()).isEqualTo("body-1");
    }

    @Test
    void insertImageAfterAnchorResolvesTopLevelSectionFromChineseOrdinalTitle() {
        DocumentAnchorResolver resolver = new DocumentAnchorResolver(null);

        ResolvedDocumentAnchor anchor = resolver.resolve(
                Artifact.builder().externalUrl("https://example.com/docx/doc123").build(),
                mediaInsertSnapshot(),
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.INSERT_MEDIA)
                        .semanticAction(DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR)
                        .userInstruction("第一章后插入一张广州的图片")
                        .anchorSpec(DocumentAnchorSpec.builder()
                                .anchorKind(DocumentAnchorKind.SECTION)
                                .matchMode(DocumentAnchorMatchMode.BY_HEADING_TITLE)
                                .headingTitle("第一章")
                                .build())
                        .build()
        );

        assertThat(anchor.getAnchorType()).isEqualTo(DocumentAnchorType.BLOCK);
        assertThat(anchor.getSectionAnchor()).isNotNull();
        assertThat(anchor.getSectionAnchor().getHeadingText()).isEqualTo("一、发展现状与总体态势");
        assertThat(anchor.getInsertionBlockId()).isEqualTo("body-1");
    }

    private DocumentStructureSnapshot snapshot() {
        Map<String, DocumentStructureNode> blockIndex = new LinkedHashMap<>();
        blockIndex.put("meta-1", DocumentStructureNode.builder().blockId("meta-1").blockType("p").plainText("作者：张三").build());
        blockIndex.put("heading-1", DocumentStructureNode.builder().blockId("heading-1").blockType("heading").headingLevel(2).plainText("一、项目背景").titleText("一、项目背景").build());
        blockIndex.put("body-1", DocumentStructureNode.builder().blockId("body-1").blockType("p").plainText("正文内容").build());
        return DocumentStructureSnapshot.builder()
                .docId("doc123")
                .blockIndex(blockIndex)
                .headingIndex(Map.of("heading-1", blockIndex.get("heading-1")))
                .topLevelSequence(List.of("heading-1"))
                .rawOutlineXml("<h2 id=\"heading-1\">一、项目背景</h2>")
                .rawFullXml("<doc><p id=\"meta-1\">作者：张三</p><h2 id=\"heading-1\">一、项目背景</h2><p id=\"body-1\">正文内容</p></doc>")
                .rawFullMarkdown("作者：张三\n\n## 一、项目背景\n\n正文内容")
                .build();
    }

    private DocumentStructureSnapshot duplicateTextSnapshot() {
        Map<String, DocumentStructureNode> blockIndex = new LinkedHashMap<>();
        blockIndex.put("heading-1", heading("heading-1", "一、项目背景"));
        blockIndex.put("body-1", paragraph("body-1", "重复内容"));
        blockIndex.put("body-2", paragraph("body-2", "重复内容"));
        return DocumentStructureSnapshot.builder()
                .docId("doc-text")
                .blockIndex(blockIndex)
                .headingIndex(Map.of("heading-1", blockIndex.get("heading-1")))
                .orderedBlockIds(List.of("heading-1", "body-1", "body-2"))
                .topLevelSequence(List.of("heading-1"))
                .build();
    }

    private DocumentStructureSnapshot duplicateHeadingSnapshot() {
        Map<String, DocumentStructureNode> blockIndex = new LinkedHashMap<>();
        blockIndex.put("heading-1", heading("heading-1", "1.1 项目背景"));
        blockIndex.put("heading-2", heading("heading-2", "2.1 项目背景"));
        return DocumentStructureSnapshot.builder()
                .docId("doc-heading")
                .blockIndex(blockIndex)
                .headingIndex(Map.of(
                        "heading-1", blockIndex.get("heading-1"),
                        "heading-2", blockIndex.get("heading-2")
                ))
                .orderedBlockIds(List.of("heading-1", "heading-2"))
                .topLevelSequence(List.of("heading-1", "heading-2"))
                .build();
    }

    private DocumentStructureSnapshot duplicateMediaSnapshot() {
        Map<String, DocumentStructureNode> blockIndex = new LinkedHashMap<>();
        blockIndex.put("heading-1", heading("heading-1", "一、插图"));
        blockIndex.put("img-1", DocumentStructureNode.builder().blockId("img-1").blockType("image").plainText("示意图").build());
        blockIndex.put("img-2", DocumentStructureNode.builder().blockId("img-2").blockType("image").plainText("示意图").build());
        return DocumentStructureSnapshot.builder()
                .docId("doc-media")
                .blockIndex(blockIndex)
                .headingIndex(Map.of("heading-1", blockIndex.get("heading-1")))
                .orderedBlockIds(List.of("heading-1", "img-1", "img-2"))
                .topLevelSequence(List.of("heading-1"))
                .build();
    }

    private DocumentStructureSnapshot nestedHeadingSnapshot() {
        Map<String, DocumentStructureNode> blockIndex = new LinkedHashMap<>();
        blockIndex.put("heading-1", heading("heading-1", "一、发展现状"));
        blockIndex.put("heading-1-1", heading3("heading-1-1", "1.1 总体复苏态势"));
        blockIndex.put("heading-1-2", heading3("heading-1-2", "1.2 文旅融合新业态发展"));
        blockIndex.put("heading-1-3", heading3("heading-1-3", "1.3 客源市场结构"));
        return DocumentStructureSnapshot.builder()
                .docId("doc-nested")
                .blockIndex(blockIndex)
                .headingIndex(Map.of(
                        "heading-1", blockIndex.get("heading-1"),
                        "heading-1-1", blockIndex.get("heading-1-1"),
                        "heading-1-2", blockIndex.get("heading-1-2"),
                        "heading-1-3", blockIndex.get("heading-1-3")
                ))
                .parentHeadingIndex(Map.of(
                        "heading-1-1", "heading-1",
                        "heading-1-2", "heading-1",
                        "heading-1-3", "heading-1"
                ))
                .childrenHeadingIndex(Map.of(
                        "heading-1", List.of("heading-1-1", "heading-1-2", "heading-1-3")
                ))
                .headingPathIndexById(Map.of(
                        "heading-1", List.of("heading-1"),
                        "heading-1-1", List.of("heading-1", "heading-1-1"),
                        "heading-1-2", List.of("heading-1", "heading-1-2"),
                        "heading-1-3", List.of("heading-1", "heading-1-3")
                ))
                .headingPathNumberIndex(Map.of(
                        "1", "heading-1",
                        "1.1", "heading-1-1",
                        "1.2", "heading-1-2",
                        "1.3", "heading-1-3",
                        "1/1.1", "heading-1-1",
                        "1/1.2", "heading-1-2",
                        "1/1.3", "heading-1-3"
                ))
                .orderedBlockIds(List.of("heading-1", "heading-1-1", "heading-1-2", "heading-1-3"))
                .blockOrderIndex(Map.of(
                        "heading-1", 0,
                        "heading-1-1", 1,
                        "heading-1-2", 2,
                        "heading-1-3", 3
                ))
                .topLevelSequence(List.of("heading-1"))
                .build();
    }

    private DocumentStructureSnapshot mediaInsertSnapshot() {
        Map<String, DocumentStructureNode> blockIndex = new LinkedHashMap<>();
        blockIndex.put("heading-1", heading("heading-1", "一、发展现状与总体态势"));
        blockIndex.put("body-1", paragraph("body-1", "这里是章节正文"));
        return DocumentStructureSnapshot.builder()
                .docId("doc-media-insert")
                .blockIndex(blockIndex)
                .headingIndex(Map.of("heading-1", blockIndex.get("heading-1")))
                .orderedBlockIds(List.of("heading-1", "body-1"))
                .sectionBlockIds(Map.of("heading-1", List.of("heading-1", "body-1")))
                .topLevelSequence(List.of("heading-1"))
                .build();
    }

    private DocumentStructureNode heading(String blockId, String title) {
        return DocumentStructureNode.builder()
                .blockId(blockId)
                .blockType("heading")
                .headingLevel(2)
                .plainText(title)
                .titleText(title)
                .build();
    }

    private DocumentStructureNode paragraph(String blockId, String text) {
        return DocumentStructureNode.builder()
                .blockId(blockId)
                .blockType("p")
                .plainText(text)
                .build();
    }

    private DocumentStructureNode heading3(String blockId, String title) {
        return DocumentStructureNode.builder()
                .blockId(blockId)
                .blockType("heading")
                .headingLevel(3)
                .plainText(title)
                .titleText(title)
                .build();
    }
}
