package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.model.entity.DocumentStructureNode;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.skills.lark.doc.LarkDocFetchResult;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentStructureSnapshotBuilder {

    private final LarkDocTool larkDocTool;
    private final DocumentStructureParser structureParser;

    public DocumentStructureSnapshotBuilder(LarkDocTool larkDocTool, DocumentStructureParser structureParser) {
        this.larkDocTool = larkDocTool;
        this.structureParser = structureParser;
    }

    public DocumentStructureSnapshot build(Artifact artifact) {
        String docRef = artifact.getExternalUrl() != null && !artifact.getExternalUrl().isBlank()
                ? artifact.getExternalUrl()
                : artifact.getDocumentId();
        String docId = larkDocTool.extractDocumentId(docRef);
        LarkDocFetchResult outline = larkDocTool.fetchDocOutline(docRef);
        LarkDocFetchResult fullMarkdown = larkDocTool.fetchDocFullMarkdown(docRef);
        List<DocumentStructureParser.HeadingBlock> headings = structureParser.parseHeadings(outline.getContent());
        LarkDocFetchResult fullXml = null;
        try {
            fullXml = larkDocTool.fetchDocFull(docRef, "with-ids");
        } catch (RuntimeException exception) {
            // 轻量降级：富媒体路径优先保证可执行，不因为整篇 XML 抓取失败而中断。
        }
        List<String> allBlockIds = fullXml == null ? List.of() : structureParser.parseBlockIds(fullXml.getContent());
        List<DocumentStructureParser.BlockNode> blocks = fullXml == null
                ? List.of()
                : structureParser.parseBlockNodes(fullXml.getContent());
        Map<String, DocumentStructureNode> headingIndex = new LinkedHashMap<>();
        Map<String, DocumentStructureNode> blockIndex = new LinkedHashMap<>();
        List<DocumentStructureNode> rootNodes = new ArrayList<>();
        List<String> topLevelSequence = new ArrayList<>();

        int minHeadingLevel = headings.stream().mapToInt(DocumentStructureParser.HeadingBlock::getLevel).min().orElse(2);
        String currentTopLevelId = null;
        for (int i = 0; i < headings.size(); i++) {
            DocumentStructureParser.HeadingBlock heading = headings.get(i);
            String prevId = i > 0 ? headings.get(i - 1).getBlockId() : null;
            String nextId = i + 1 < headings.size() ? headings.get(i + 1).getBlockId() : null;
            if (heading.getLevel() == minHeadingLevel) {
                currentTopLevelId = heading.getBlockId();
            }
            int topLevelIndex = heading.getLevel() == minHeadingLevel ? topLevelSequence.size() + 1 : topLevelSequence.size();
            DocumentStructureNode node = DocumentStructureNode.builder()
                    .blockId(heading.getBlockId())
                    .blockType("heading")
                    .headingLevel(heading.getLevel())
                    .titleText(heading.getText())
                    .plainText(heading.getText())
                    .topLevelAncestorId(currentTopLevelId)
                    .prevSiblingId(prevId)
                    .nextSiblingId(nextId)
                    .topLevelIndex(topLevelIndex <= 0 ? null : topLevelIndex)
                    .build();
            headingIndex.put(heading.getBlockId(), node);
            blockIndex.put(heading.getBlockId(), node);
            if (heading.getLevel() == minHeadingLevel) {
                rootNodes.add(node);
                topLevelSequence.add(heading.getBlockId());
            }
        }
            for (String blockId : allBlockIds) {
                DocumentStructureParser.BlockNode parsedBlock = blocks.stream()
                    .filter(block -> blockId.equals(block.getBlockId()))
                    .findFirst()
                    .orElse(null);
            blockIndex.putIfAbsent(blockId, DocumentStructureNode.builder()
                    .blockId(blockId)
                    .blockType(parsedBlock == null ? "block" : parsedBlock.getTagName())
                    .plainText(parsedBlock == null ? null : parsedBlock.getPlainText())
                    .build());
        }
        return DocumentStructureSnapshot.builder()
                .docId(docId)
                .revisionId(fullMarkdown.getRevisionId())
                .rootNodes(rootNodes)
                .headingIndex(headingIndex)
                .blockIndex(blockIndex)
                .topLevelSequence(topLevelSequence)
                .rawOutlineXml(outline.getContent())
                .rawFullXml(fullXml == null ? null : fullXml.getContent())
                .rawFullMarkdown(fullMarkdown.getContent())
                .build();
    }
}
