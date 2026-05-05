package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.model.entity.DocumentStructureNode;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.skills.lark.doc.LarkDocFetchResult;
import com.lark.imcollab.skills.lark.doc.LarkDocReadGateway;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentStructureSnapshotBuilder {

    private static final Pattern DOC_URL_PATTERN = Pattern.compile("/(?:docx|wiki)/([A-Za-z0-9]+)");
    private static final Pattern DECIMAL_HEADING_NUMBER_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)*)");
    private static final Pattern CHINESE_HEADING_NUMBER_PATTERN = Pattern.compile("^第([一二三四五六七八九十百千万0-9]+)(章|节|部分)");
    private static final Pattern CHINESE_LIST_HEADING_NUMBER_PATTERN = Pattern.compile("^([一二三四五六七八九十百千万]+)[、.]");

    private final LarkDocReadGateway readGateway;
    private final DocumentStructureParser structureParser;

    public DocumentStructureSnapshotBuilder(LarkDocReadGateway readGateway, DocumentStructureParser structureParser) {
        this.readGateway = readGateway;
        this.structureParser = structureParser;
    }

    /**
     * 轻量主快照：只抓 outline，不循环抓所有 section with-ids。
     * sectionBlockIds 按需通过 fetchSection() 填充。
     */
    public DocumentStructureSnapshot build(Artifact artifact) {
        String docRef = resolveDocRef(artifact);
        String docId = extractDocumentId(docRef);
        LarkDocFetchResult outline = readGateway.fetchDocOutline(docRef);
        List<DocumentStructureParser.HeadingBlock> headings = structureParser.parseHeadings(outline.getContent());

        Map<String, DocumentStructureNode> headingIndex = new LinkedHashMap<>();
        Map<String, DocumentStructureNode> blockIndex = new LinkedHashMap<>();
        Map<String, Integer> blockOrderIndex = new LinkedHashMap<>();
        Map<String, String> parentHeadingIndex = new LinkedHashMap<>();
        Map<String, List<String>> childrenHeadingIndex = new LinkedHashMap<>();
        Map<String, List<String>> headingPathIndexById = new LinkedHashMap<>();
        Map<String, String> headingPathNumberIndex = new LinkedHashMap<>();
        Map<String, List<String>> headingTitleIndexNormalized = new LinkedHashMap<>();
        Map<String, String> headingCompositeKeyIndex = new LinkedHashMap<>();
        List<String> topLevelSequence = new ArrayList<>();
        List<String> orderedBlockIds = new ArrayList<>();
        Map<String, String> headingNumberById = new HashMap<>();
        List<DocumentStructureNode> nodes = new ArrayList<>();

        int minLevel = headings.stream().mapToInt(DocumentStructureParser.HeadingBlock::getLevel).min().orElse(2);
        List<DocumentStructureNode> stack = new ArrayList<>();

        for (int i = 0; i < headings.size(); i++) {
            DocumentStructureParser.HeadingBlock h = headings.get(i);
            DocumentStructureNode node = DocumentStructureNode.builder()
                    .blockId(h.getBlockId())
                    .blockType("heading")
                    .headingLevel(h.getLevel())
                    .titleText(h.getText())
                    .plainText(h.getText())
                    .children(new ArrayList<>())
                    .build();
            while (!stack.isEmpty() && stack.get(stack.size() - 1).getHeadingLevel() >= h.getLevel()) {
                stack.remove(stack.size() - 1);
            }
            DocumentStructureNode parent = stack.isEmpty() ? null : stack.get(stack.size() - 1);
            if (parent != null) {
                node.setParentBlockId(parent.getBlockId());
                parentHeadingIndex.put(node.getBlockId(), parent.getBlockId());
                childrenHeadingIndex.computeIfAbsent(parent.getBlockId(), ignored -> new ArrayList<>()).add(node.getBlockId());
                if (parent.getChildren() != null) {
                    parent.getChildren().add(node.getBlockId());
                }
            }
            if (h.getLevel() == minLevel) {
                topLevelSequence.add(node.getBlockId());
            }
            headingNumberById.put(node.getBlockId(), extractHeadingNumber(h.getText()));
            nodes.add(node);
            stack.add(node);
        }

        for (int i = 0; i < nodes.size(); i++) {
            DocumentStructureNode node = nodes.get(i);
            String blockId = node.getBlockId();
            String prevId = i > 0 ? nodes.get(i - 1).getBlockId() : null;
            String nextId = i + 1 < nodes.size() ? nodes.get(i + 1).getBlockId() : null;
            String topLevelAncestorId = resolveTopLevelAncestorId(node, parentHeadingIndex, topLevelSequence);
            node.setPrevSiblingId(prevId);
            node.setNextSiblingId(nextId);
            node.setTopLevelAncestorId(topLevelAncestorId);
            node.setTopLevelIndex(resolveTopLevelIndex(topLevelSequence, topLevelAncestorId));
            List<String> headingPath = buildHeadingPath(blockId, parentHeadingIndex);
            headingPathIndexById.put(blockId, headingPath);
            String headingNumber = headingNumberById.get(blockId);
            List<String> pathNumbers = buildPathNumbers(headingPath, headingNumberById);
            if (headingNumber != null && !headingNumber.isBlank()) {
                headingPathNumberIndex.put(headingNumber, blockId);
            }
            if (!pathNumbers.isEmpty()) {
                headingPathNumberIndex.put(String.join("/", pathNumbers), blockId);
                if (pathNumbers.size() == 1) {
                    headingPathNumberIndex.put(pathNumbers.get(0), blockId);
                }
            }
            String normalizedTitle = normalize(node.getTitleText());
            headingTitleIndexNormalized.computeIfAbsent(normalizedTitle, ignored -> new ArrayList<>()).add(blockId);
            String compositeKey = compositeKey(node.getParentBlockId(), normalizedTitle, node.getHeadingLevel());
            headingCompositeKeyIndex.put(compositeKey, blockId);
            headingIndex.put(blockId, node);
            blockIndex.put(blockId, node);
            orderedBlockIds.add(blockId);
            blockOrderIndex.put(blockId, orderedBlockIds.size() - 1);
        }

        List<DocumentStructureNode> rootNodes = topLevelSequence.stream()
                .map(headingIndex::get)
                .filter(java.util.Objects::nonNull)
                .toList();

        return DocumentStructureSnapshot.builder()
                .docId(docId)
                .revisionId(outline.getRevisionId())
                .rootNodes(rootNodes)
                .headingIndex(headingIndex)
                .blockIndex(blockIndex)
                .topLevelSequence(topLevelSequence)
                .orderedBlockIds(orderedBlockIds)
                .blockOrderIndex(blockOrderIndex)
                .parentHeadingIndex(parentHeadingIndex)
                .childrenHeadingIndex(childrenHeadingIndex)
                .headingPathIndexById(headingPathIndexById)
                .headingPathNumberIndex(headingPathNumberIndex)
                .headingTitleIndexNormalized(headingTitleIndexNormalized)
                .headingCompositeKeyIndex(headingCompositeKeyIndex)
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
            LarkDocFetchResult sectionXml = readGateway.fetchDocSection(docRef, headingBlockId, "with-ids");
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
                        if (snapshot.getBlockOrderIndex() != null) {
                            snapshot.getBlockOrderIndex().put(b.getBlockId(), snapshot.getOrderedBlockIds().size() - 1);
                        }
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

    private String extractDocumentId(String docIdOrUrl) {
        if (docIdOrUrl == null || docIdOrUrl.isBlank()) {
            return docIdOrUrl;
        }
        String trimmed = docIdOrUrl.trim();
        Matcher matcher = DOC_URL_PATTERN.matcher(trimmed);
        return matcher.find() ? matcher.group(1) : trimmed;
    }

    private String resolveTopLevelAncestorId(DocumentStructureNode node, Map<String, String> parentHeadingIndex,
                                             List<String> topLevelSequence) {
        if (node == null) {
            return null;
        }
        String current = node.getBlockId();
        String parent = parentHeadingIndex.get(current);
        while (parent != null) {
            current = parent;
            parent = parentHeadingIndex.get(current);
        }
        return topLevelSequence.contains(current) ? current : node.getBlockId();
    }

    private Integer resolveTopLevelIndex(List<String> topLevelSequence, String topLevelAncestorId) {
        int idx = topLevelSequence.indexOf(topLevelAncestorId);
        return idx >= 0 ? idx + 1 : null;
    }

    private List<String> buildHeadingPath(String headingBlockId, Map<String, String> parentHeadingIndex) {
        List<String> path = new ArrayList<>();
        String current = headingBlockId;
        while (current != null) {
            path.add(current);
            current = parentHeadingIndex.get(current);
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }

    private List<String> buildPathNumbers(List<String> headingPath, Map<String, String> headingNumberById) {
        List<String> numbers = new ArrayList<>();
        for (String headingId : headingPath) {
            String headingNumber = headingNumberById.get(headingId);
            if (headingNumber != null && !headingNumber.isBlank()) {
                numbers.add(headingNumber);
            }
        }
        return List.copyOf(numbers);
    }

    private String extractHeadingNumber(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        Matcher decimalMatcher = DECIMAL_HEADING_NUMBER_PATTERN.matcher(title.trim());
        if (decimalMatcher.find()) {
            return decimalMatcher.group(1);
        }
        Matcher chineseMatcher = CHINESE_HEADING_NUMBER_PATTERN.matcher(title.trim());
        if (chineseMatcher.find()) {
            Integer ordinal = parseChineseOrArabicOrdinal(chineseMatcher.group(1));
            if (ordinal != null) {
                return String.valueOf(ordinal);
            }
        }
        Matcher chineseListMatcher = CHINESE_LIST_HEADING_NUMBER_PATTERN.matcher(title.trim());
        if (chineseListMatcher.find()) {
            Integer ordinal = parseChineseOrArabicOrdinal(chineseListMatcher.group(1));
            if (ordinal != null) {
                return String.valueOf(ordinal);
            }
        }
        return null;
    }

    private Integer parseChineseOrArabicOrdinal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return switch (value) {
                case "一" -> 1;
                case "二" -> 2;
                case "三" -> 3;
                case "四" -> 4;
                case "五" -> 5;
                case "六" -> 6;
                case "七" -> 7;
                case "八" -> 8;
                case "九" -> 9;
                case "十" -> 10;
                default -> null;
            };
        }
    }

    private String compositeKey(String parentHeadingId, String normalizedTitle, Integer headingLevel) {
        return (parentHeadingId == null ? "ROOT" : parentHeadingId)
                + "|" + (normalizedTitle == null ? "" : normalizedTitle)
                + "|" + (headingLevel == null ? -1 : headingLevel);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
