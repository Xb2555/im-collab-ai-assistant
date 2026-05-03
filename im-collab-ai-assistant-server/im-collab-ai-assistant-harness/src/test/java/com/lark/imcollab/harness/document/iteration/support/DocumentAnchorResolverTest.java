package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.model.entity.DocumentBlockAnchor;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.DocumentStructureNode;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentAnchorResolverTest {

    @Test
    void deleteMetadataAtHeadResolvesAuthorBlockInsteadOfFirstHeading() {
        DocumentAnchorResolver resolver = new DocumentAnchorResolver(new DocumentStructureParser());

        ResolvedDocumentAnchor anchor = resolver.resolve(
                Artifact.builder().externalUrl("https://example.com/docx/doc123").build(),
                snapshot(),
                DocumentEditIntent.builder()
                        .intentType(DocumentIterationIntentType.DELETE)
                        .semanticAction(DocumentSemanticActionType.DELETE_METADATA_AT_DOCUMENT_HEAD)
                        .userInstruction("删除开头的作者信息")
                        .parameters(Map.of(
                                "targetRegion", "document_head",
                                "targetSemantic", "metadata",
                                "targetKeywords", "作者信息|作者"
                        ))
                        .build()
        );

        DocumentBlockAnchor blockAnchor = anchor.getBlockAnchor();
        assertThat(anchor.getPreview()).isEqualTo("作者：张三");
        assertThat(blockAnchor.getBlockId()).isEqualTo("meta-1");
        assertThat(blockAnchor.getPlainText()).isEqualTo("作者：张三");
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
}
