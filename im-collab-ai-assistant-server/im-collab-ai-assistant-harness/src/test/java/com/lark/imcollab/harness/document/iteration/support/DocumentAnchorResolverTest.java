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
}
