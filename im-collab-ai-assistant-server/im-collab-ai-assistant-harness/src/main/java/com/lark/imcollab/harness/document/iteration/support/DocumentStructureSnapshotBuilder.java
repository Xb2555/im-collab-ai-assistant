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
        List<DocumentStructureParser.HeadingBlock> headings = structureParser.parseHeadings(outline.getContent());

        Map<String, DocumentStructureNode> headingIndex = new LinkedHashMap<>();
        Map<String, DocumentStructureNode> blockIndex = new LinkedHashMap<>();
        Map<String, List<String>> sectionBlockIds = new LinkedHashMap<>();
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

        // 对每个 top-level section 抓一次轻量 section XML，填充 sectionBlockIds 和 blockIndex
        for (String headingId : topLevelSequence) {
            try {
                LarkDocFetchResult sectionXml = larkDocTool.fetchDocSection(docRef, headingId, "with-ids");
                List<String> ids = structureParser.parseBlockIds(sectionXml.getContent());
                List<DocumentStructureParser.BlockNode> sectionBlocks = structureParser.parseBlockNodes(sectionXml.getContent());
                if (!ids.isEmpty()) {
                    sectionBlockIds.put(headingId, List.copyOf(ids));
                    for (DocumentStructureParser.BlockNode b : sectionBlocks) {
                        blockIndex.putIfAbsent(b.getBlockId(), DocumentStructureNode.builder()
                                .blockId(b.getBlockId())
                                .blockType(b.getTagName())
                                .plainText(b.getPlainText())
                                .topLevelAncestorId(headingId)
                                .build());
                    }
                }
            } catch (RuntimeException ignored) {
                // section fetch 失败时降级：只保留 heading 本身
                sectionBlockIds.put(headingId, List.of(headingId));
            }
        }

        return DocumentStructureSnapshot.builder()
                .docId(docId)
                .revisionId(outline.getRevisionId())
                .rootNodes(rootNodes)
                .headingIndex(headingIndex)
                .blockIndex(blockIndex)
                .topLevelSequence(topLevelSequence)
                .sectionBlockIds(sectionBlockIds)
                .rawOutlineXml(outline.getContent())
                .rawFullXml(null)
                .rawFullMarkdown(null)
                .build();
    }
}
