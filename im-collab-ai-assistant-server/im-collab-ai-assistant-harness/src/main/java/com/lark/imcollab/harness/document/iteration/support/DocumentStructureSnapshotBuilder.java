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

    /**
     * 轻量主快照：只抓 outline，不循环抓所有 section with-ids。
     * sectionBlockIds 按需通过 fetchSection() 填充。
     */
    public DocumentStructureSnapshot build(Artifact artifact) {
        String docRef = resolveDocRef(artifact);
        String docId = larkDocTool.extractDocumentId(docRef);
        LarkDocFetchResult outline = larkDocTool.fetchDocOutline(docRef);
        List<DocumentStructureParser.HeadingBlock> headings = structureParser.parseHeadings(outline.getContent());

        Map<String, DocumentStructureNode> headingIndex = new LinkedHashMap<>();
        Map<String, DocumentStructureNode> blockIndex = new LinkedHashMap<>();
        List<DocumentStructureNode> rootNodes = new ArrayList<>();
        List<String> topLevelSequence = new ArrayList<>();
        List<String> orderedBlockIds = new ArrayList<>();

        int minLevel = headings.stream().mapToInt(DocumentStructureParser.HeadingBlock::getLevel).min().orElse(2);
        String currentTopLevelId = null;

        for (int i = 0; i < headings.size(); i++) {
            DocumentStructureParser.HeadingBlock h = headings.get(i);
            String prevId = i > 0 ? headings.get(i - 1).getBlockId() : null;
            String nextId = i + 1 < headings.size() ? headings.get(i + 1).getBlockId() : null;
            if (h.getLevel() == minLevel) {
                currentTopLevelId = h.getBlockId();
            }
            int topLevelIndex = h.getLevel() == minLevel ? topLevelSequence.size() + 1 : topLevelSequence.size();
            DocumentStructureNode node = DocumentStructureNode.builder()
                    .blockId(h.getBlockId())
                    .blockType("heading")
                    .headingLevel(h.getLevel())
                    .titleText(h.getText())
                    .plainText(h.getText())
                    .topLevelAncestorId(currentTopLevelId)
                    .prevSiblingId(prevId)
                    .nextSiblingId(nextId)
                    .topLevelIndex(topLevelIndex <= 0 ? null : topLevelIndex)
                    .build();
            headingIndex.put(h.getBlockId(), node);
            blockIndex.put(h.getBlockId(), node);
            orderedBlockIds.add(h.getBlockId());
            if (h.getLevel() == minLevel) {
                rootNodes.add(node);
                topLevelSequence.add(h.getBlockId());
            }
        }

        return DocumentStructureSnapshot.builder()
                .docId(docId)
                .revisionId(outline.getRevisionId())
                .rootNodes(rootNodes)
                .headingIndex(headingIndex)
                .blockIndex(blockIndex)
                .topLevelSequence(topLevelSequence)
                .orderedBlockIds(orderedBlockIds)
                .sectionBlockIds(new LinkedHashMap<>())
                .rawOutlineXml(outline.getContent())
                .rawFullXml(null)
                .rawFullMarkdown(null)
                .build();
    }

    /**
     * 按需抓取单个 section 的 block 明细，填充到已有 snapshot。
     */
    public void fetchSectionDetail(DocumentStructureSnapshot snapshot, String headingBlockId, String docRef) {
        if (snapshot.getSectionBlockIds().containsKey(headingBlockId)) {
            return;
        }
        try {
            LarkDocFetchResult sectionXml = larkDocTool.fetchDocSection(docRef, headingBlockId, "with-ids");
            List<String> ids = structureParser.parseBlockIds(sectionXml.getContent());
            List<DocumentStructureParser.BlockNode> blocks = structureParser.parseBlockNodes(sectionXml.getContent());
            if (!ids.isEmpty()) {
                snapshot.getSectionBlockIds().put(headingBlockId, List.copyOf(ids));
                for (DocumentStructureParser.BlockNode b : blocks) {
                    snapshot.getBlockIndex().putIfAbsent(b.getBlockId(), DocumentStructureNode.builder()
                            .blockId(b.getBlockId())
                            .blockType(b.getTagName())
                            .plainText(b.getPlainText())
                            .topLevelAncestorId(headingBlockId)
                            .build());
                    if (!snapshot.getOrderedBlockIds().contains(b.getBlockId())) {
                        snapshot.getOrderedBlockIds().add(b.getBlockId());
                    }
                }
            } else {
                snapshot.getSectionBlockIds().put(headingBlockId, List.of(headingBlockId));
            }
        } catch (RuntimeException ignored) {
            snapshot.getSectionBlockIds().put(headingBlockId, List.of(headingBlockId));
        }
    }

    private String resolveDocRef(Artifact artifact) {
        return artifact.getExternalUrl() != null && !artifact.getExternalUrl().isBlank()
                ? artifact.getExternalUrl()
                : artifact.getDocumentId();
    }
}
