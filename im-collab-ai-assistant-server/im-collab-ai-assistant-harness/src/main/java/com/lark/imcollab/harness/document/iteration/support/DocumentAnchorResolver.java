package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.model.entity.DocumentBlockAnchor;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.DocumentSectionAnchor;
import com.lark.imcollab.common.model.entity.DocumentStructureNode;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.entity.DocumentTextAnchor;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.enums.DocumentAnchorType;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class DocumentAnchorResolver {

    private final DocumentStructureParser structureParser;

    public DocumentAnchorResolver(DocumentStructureParser structureParser) {
        this.structureParser = structureParser;
    }

    public ResolvedDocumentAnchor resolve(Artifact artifact, DocumentStructureSnapshot snapshot, DocumentEditIntent intent) {
        DocumentSemanticActionType action = intent.getSemanticAction();
        if (action == DocumentSemanticActionType.INSERT_METADATA_AT_DOCUMENT_HEAD) {
            return ResolvedDocumentAnchor.builder()
                    .anchorType(DocumentAnchorType.DOCUMENT_HEAD)
                    .sectionAnchor(firstSectionAnchor(snapshot))
                    .preview("DOC_HEAD")
                    .build();
        }
        if (action == DocumentSemanticActionType.APPEND_SECTION_TO_DOCUMENT_END) {
            return ResolvedDocumentAnchor.builder()
                    .anchorType(DocumentAnchorType.DOCUMENT_TAIL)
                    .preview("DOC_TAIL")
                    .build();
        }
        if (action == DocumentSemanticActionType.DELETE_METADATA_AT_DOCUMENT_HEAD) {
            DocumentBlockAnchor blockAnchor = resolveHeadMetadataBlock(snapshot, intent);
            return ResolvedDocumentAnchor.builder()
                    .anchorType(DocumentAnchorType.BLOCK)
                    .blockAnchor(blockAnchor)
                    .preview(blockAnchor == null ? "" : blockAnchor.getPlainText())
                    .build();
        }
        if (action == DocumentSemanticActionType.REWRITE_METADATA_AT_DOCUMENT_HEAD) {
            DocumentBlockAnchor blockAnchor = resolveHeadMetadataBlock(snapshot, intent);
            return ResolvedDocumentAnchor.builder()
                    .anchorType(DocumentAnchorType.BLOCK)
                    .blockAnchor(blockAnchor)
                    .preview(blockAnchor == null ? "" : blockAnchor.getPlainText())
                    .build();
        }
        if (targetsSection(action)) {
            DocumentSectionAnchor sectionAnchor = resolveSectionAnchor(snapshot, intent.getUserInstruction());
            return ResolvedDocumentAnchor.builder()
                    .anchorType(DocumentAnchorType.SECTION)
                    .sectionAnchor(sectionAnchor)
                    .preview(sectionPreview(sectionAnchor))
                    .build();
        }
        if (targetsText(intent)) {
            DocumentTextAnchor textAnchor = resolveTextAnchor(snapshot, intent.getUserInstruction());
            return ResolvedDocumentAnchor.builder()
                    .anchorType(DocumentAnchorType.TEXT)
                    .textAnchor(textAnchor)
                    .preview(textAnchor == null ? "" : textAnchor.getMatchedText())
                    .build();
        }
        DocumentBlockAnchor blockAnchor = resolveBlockAnchor(snapshot, intent.getUserInstruction());
        return ResolvedDocumentAnchor.builder()
                .anchorType(DocumentAnchorType.BLOCK)
                .blockAnchor(blockAnchor)
                .preview(blockAnchor == null ? "" : blockAnchor.getPlainText())
                .build();
    }

    private boolean targetsSection(DocumentSemanticActionType action) {
        return switch (action) {
            case INSERT_SECTION_BEFORE_SECTION, REWRITE_SECTION_BODY, DELETE_SECTION_BODY, DELETE_WHOLE_SECTION, MOVE_SECTION -> true;
            default -> false;
        };
    }

    private boolean targetsText(DocumentEditIntent intent) {
        return intent.getSemanticAction() == DocumentSemanticActionType.REWRITE_INLINE_TEXT
                || intent.getSemanticAction() == DocumentSemanticActionType.DELETE_INLINE_TEXT;
    }

    private DocumentBlockAnchor resolveHeadMetadataBlock(DocumentStructureSnapshot snapshot, DocumentEditIntent intent) {
        if (snapshot == null || snapshot.getBlockIndex() == null || snapshot.getBlockIndex().isEmpty()) {
            return null;
        }
        List<String> allBlockIds = structureParser.parseBlockIds(snapshot.getRawFullXml());
        if (allBlockIds.isEmpty()) {
            return null;
        }
        String firstHeadingId = snapshot.getTopLevelSequence() == null || snapshot.getTopLevelSequence().isEmpty()
                ? null
                : snapshot.getTopLevelSequence().get(0);
        int firstHeadingIndex = firstHeadingId == null ? allBlockIds.size() : allBlockIds.indexOf(firstHeadingId);
        if (firstHeadingIndex < 0) {
            firstHeadingIndex = allBlockIds.size();
        }
        Set<String> targetKeywords = resolveIntentKeywords(intent);
        for (int i = 0; i < firstHeadingIndex; i++) {
            String blockId = allBlockIds.get(i);
            DocumentStructureNode node = snapshot.getBlockIndex().get(blockId);
            if (!isHeadMetadataCandidate(node, targetKeywords)) {
                continue;
            }
            return DocumentBlockAnchor.builder()
                    .blockId(blockId)
                    .blockType(node.getBlockType())
                    .plainText(node.getPlainText())
                    .nextBlockId(i + 1 < allBlockIds.size() ? allBlockIds.get(i + 1) : null)
                    .prevBlockId(i > 0 ? allBlockIds.get(i - 1) : null)
                    .build();
        }
        return null;
    }

    private DocumentSectionAnchor resolveSectionAnchor(DocumentStructureSnapshot snapshot, String instruction) {
        List<DocumentStructureParser.HeadingBlock> headings = structureParser.parseHeadings(snapshot.getRawOutlineXml());
        DocumentStructureParser.HeadingBlock matched = structureParser.resolveOrdinalHeading(instruction, headings);
        if (matched == null) {
            List<DocumentStructureParser.HeadingBlock> matches = structureParser.matchHeadings(instruction, headings);
            matched = matches.isEmpty() ? null : matches.get(0);
        }
        if (matched == null) {
            return null;
        }
        List<String> allBlockIds = sectionAllBlockIds(snapshot, matched.getBlockId());
        List<String> bodyBlockIds = allBlockIds.size() <= 1 ? List.of() : allBlockIds.subList(1, allBlockIds.size());
        int topLevelIndex = resolveTopLevelIndex(snapshot, matched.getBlockId());
        String prevTopLevelSectionId = topLevelIndex > 1 ? snapshot.getTopLevelSequence().get(topLevelIndex - 2) : null;
        String nextTopLevelSectionId = topLevelIndex > 0 && topLevelIndex < snapshot.getTopLevelSequence().size()
                ? snapshot.getTopLevelSequence().get(topLevelIndex)
                : null;
        return DocumentSectionAnchor.builder()
                .headingBlockId(matched.getBlockId())
                .headingText(matched.getText())
                .headingLevel(matched.getLevel())
                .bodyBlockIds(bodyBlockIds)
                .allBlockIds(allBlockIds)
                .topLevelIndex(topLevelIndex)
                .prevTopLevelSectionId(prevTopLevelSectionId)
                .nextTopLevelSectionId(nextTopLevelSectionId)
                .build();
    }

    private DocumentTextAnchor resolveTextAnchor(DocumentStructureSnapshot snapshot, String instruction) {
        String quoted = structureParser.extractQuotedText(instruction);
        if (quoted == null || quoted.isBlank()) {
            return DocumentTextAnchor.builder()
                    .matchedText("")
                    .matchCount(0)
                    .surroundingContext("")
                    .sourceBlockIds(List.of())
                    .build();
        }
        int matchCount = structureParser.countOccurrences(snapshot.getRawFullMarkdown(), quoted);
        return DocumentTextAnchor.builder()
                .matchedText(quoted)
                .matchCount(matchCount)
                .surroundingContext(quoted)
                .sourceBlockIds(List.of())
                .build();
    }

    private DocumentBlockAnchor resolveBlockAnchor(DocumentStructureSnapshot snapshot, String instruction) {
        String quoted = structureParser.extractQuotedText(instruction);
        if (quoted == null || quoted.isBlank()) {
            return DocumentBlockAnchor.builder().blockId(null).blockType(null).plainText("").build();
        }
        List<String> blockIds = structureParser.parseBlockIds(snapshot.getRawFullXml());
        for (String blockId : blockIds) {
            DocumentStructureNode node = snapshot.getBlockIndex() == null ? null : snapshot.getBlockIndex().get(blockId);
            if (isProtectedBlock(node, snapshot)) {
                continue;
            }
            if (node != null && node.getPlainText() != null && node.getPlainText().contains(quoted)) {
                return DocumentBlockAnchor.builder()
                        .blockId(blockId)
                        .blockType(node.getBlockType())
                        .plainText(node.getPlainText())
                        .topLevelAncestorId(node.getTopLevelAncestorId())
                        .nextBlockId(node.getNextSiblingId())
                        .prevBlockId(node.getPrevSiblingId())
                        .build();
            }
        }
        return DocumentBlockAnchor.builder().blockId(null).blockType(null).plainText(quoted).build();
    }

    private DocumentSectionAnchor firstSectionAnchor(DocumentStructureSnapshot snapshot) {
        if (snapshot.getTopLevelSequence() == null || snapshot.getTopLevelSequence().isEmpty()) {
            return null;
        }
        String headingBlockId = snapshot.getTopLevelSequence().get(0);
        DocumentStructureNode node = snapshot.getHeadingIndex() == null ? null : snapshot.getHeadingIndex().get(headingBlockId);
        return DocumentSectionAnchor.builder()
                .headingBlockId(headingBlockId)
                .headingText(node == null ? "" : node.getTitleText())
                .headingLevel(node == null ? 2 : node.getHeadingLevel())
                .allBlockIds(sectionAllBlockIds(snapshot, headingBlockId))
                .bodyBlockIds(sectionAllBlockIds(snapshot, headingBlockId).size() <= 1 ? List.of() : sectionAllBlockIds(snapshot, headingBlockId).subList(1, sectionAllBlockIds(snapshot, headingBlockId).size()))
                .topLevelIndex(1)
                .nextTopLevelSectionId(snapshot.getTopLevelSequence().size() > 1 ? snapshot.getTopLevelSequence().get(1) : null)
                .build();
    }

    private List<String> sectionAllBlockIds(DocumentStructureSnapshot snapshot, String headingBlockId) {
        List<String> allIds = structureParser.parseBlockIds(snapshot.getRawFullXml());
        List<DocumentStructureParser.HeadingBlock> headings = structureParser.parseHeadings(snapshot.getRawOutlineXml());
        int start = allIds.indexOf(headingBlockId);
        if (start < 0) {
            return List.of(headingBlockId);
        }
        int nextHeadingIndex = allIds.size();
        for (DocumentStructureParser.HeadingBlock heading : headings) {
            if (headingBlockId.equals(heading.getBlockId())) {
                continue;
            }
            int candidate = allIds.indexOf(heading.getBlockId());
            if (candidate > start) {
                nextHeadingIndex = Math.min(nextHeadingIndex, candidate);
            }
        }
        return List.copyOf(allIds.subList(start, nextHeadingIndex));
    }

    private int resolveTopLevelIndex(DocumentStructureSnapshot snapshot, String headingBlockId) {
        if (snapshot.getTopLevelSequence() == null) {
            return 0;
        }
        int index = snapshot.getTopLevelSequence().indexOf(headingBlockId);
        if (index >= 0) {
            return index + 1;
        }
        DocumentStructureNode node = snapshot.getHeadingIndex() == null ? null : snapshot.getHeadingIndex().get(headingBlockId);
        if (node != null && node.getTopLevelAncestorId() != null) {
            index = snapshot.getTopLevelSequence().indexOf(node.getTopLevelAncestorId());
            return index >= 0 ? index + 1 : 0;
        }
        return 0;
    }

    private String sectionPreview(DocumentSectionAnchor anchor) {
        if (anchor == null) {
            return "";
        }
        return anchor.getHeadingText() == null ? "" : anchor.getHeadingText();
    }

    private boolean isHeadMetadataCandidate(DocumentStructureNode node, Set<String> targetKeywords) {
        if (node == null || isProtectedBlock(node, null)) {
            return false;
        }
        String plainText = node.getPlainText() == null ? "" : node.getPlainText().trim();
        if (plainText.isBlank()) {
            return false;
        }
        String normalizedText = normalizeLoose(plainText);
        return targetKeywords.stream()
                .map(this::normalizeLoose)
                .filter(keyword -> !keyword.isBlank())
                .anyMatch(normalizedText::contains);
    }

    private boolean isProtectedBlock(DocumentStructureNode node, DocumentStructureSnapshot snapshot) {
        if (node == null) {
            return true;
        }
        if ("heading".equalsIgnoreCase(node.getBlockType()) || node.getHeadingLevel() != null) {
            return true;
        }
        if (snapshot != null && snapshot.getHeadingIndex() != null && snapshot.getHeadingIndex().containsKey(node.getBlockId())) {
            return true;
        }
        return false;
    }

    private Set<String> resolveIntentKeywords(DocumentEditIntent intent) {
        if (intent == null || intent.getParameters() == null) {
            return Set.of();
        }
        String encodedKeywords = intent.getParameters().getOrDefault("targetKeywords", "");
        return java.util.Arrays.stream(encodedKeywords.split("\\|"))
                .map(String::trim)
                .filter(keyword -> !keyword.isBlank())
                .collect(java.util.stream.Collectors.toSet());
    }

    private String normalizeLoose(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }
}
