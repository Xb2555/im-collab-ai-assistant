package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.model.entity.DocumentAnchorSpec;
import com.lark.imcollab.common.model.entity.DocumentBlockAnchor;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.DocumentMediaAnchor;
import com.lark.imcollab.common.model.entity.DocumentSectionAnchor;
import com.lark.imcollab.common.model.entity.DocumentStructureNode;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.entity.DocumentTextAnchor;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.enums.DocumentAnchorKind;
import com.lark.imcollab.common.model.enums.DocumentAnchorMatchMode;
import com.lark.imcollab.common.model.enums.DocumentAnchorType;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import com.lark.imcollab.common.model.enums.MediaAssetType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class DocumentAnchorResolver {

    private final DocumentStructureSnapshotBuilder snapshotBuilder;

    public DocumentAnchorResolver(DocumentStructureSnapshotBuilder snapshotBuilder) {
        this.snapshotBuilder = snapshotBuilder;
    }

    public ResolvedDocumentAnchor resolve(Artifact artifact, DocumentStructureSnapshot snapshot, DocumentEditIntent intent) {
        DocumentSemanticActionType action = intent.getSemanticAction();
        DocumentAnchorSpec spec = intent.getAnchorSpec();

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
        if (action == DocumentSemanticActionType.DELETE_METADATA_AT_DOCUMENT_HEAD
                || action == DocumentSemanticActionType.REWRITE_METADATA_AT_DOCUMENT_HEAD) {
            DocumentBlockAnchor blockAnchor = resolveHeadMetadataBlock(snapshot, spec);
            return ResolvedDocumentAnchor.builder()
                    .anchorType(DocumentAnchorType.BLOCK)
                    .blockAnchor(blockAnchor)
                    .preview(blockAnchor == null ? null : blockAnchor.getPlainText())
                    .build();
        }
        if (targetsInsertAfterSection(action)) {
            DocumentSectionAnchor sectionAnchor = resolveSectionAnchor(artifact, snapshot, spec);
            if (sectionAnchor == null) {
                return unresolved("无法定位目标章节，请提供更明确的章节标题或序号");
            }
            String tailBlockId = sectionTailBlockId(sectionAnchor);
            if (tailBlockId == null) {
                return unresolved("章节 " + sectionAnchor.getHeadingText() + " 的尾部 block 未解析");
            }
            DocumentStructureNode tailNode = snapshot.getBlockIndex() == null ? null : snapshot.getBlockIndex().get(tailBlockId);
            return ResolvedDocumentAnchor.builder()
                    .anchorType(DocumentAnchorType.BLOCK)
                    .sectionAnchor(sectionAnchor)
                    .blockAnchor(DocumentBlockAnchor.builder()
                            .blockId(tailBlockId)
                            .blockType(tailNode == null ? null : tailNode.getBlockType())
                            .build())
                    .insertionBlockId(tailBlockId)
                    .preview(sectionAnchor.getHeadingText())
                    .build();
        }
        if (targetsMedia(action)) {
            MediaAssetType mediaType = mediaTypeFor(action);
            DocumentMediaAnchor mediaAnchor = resolveMediaAnchor(snapshot, spec, mediaType);
            if (mediaAnchor == null) {
                return unresolved("无法定位目标媒体节点，请提供更明确的描述");
            }
            return ResolvedDocumentAnchor.builder()
                    .anchorType(mediaAnchorType(action))
                    .mediaAnchor(mediaAnchor)
                    .preview(mediaAnchor.getPlainText())
                    .build();
        }
        if (targetsSection(action)) {
            DocumentSectionAnchor sectionAnchor = resolveSectionAnchor(artifact, snapshot, spec);
            if (sectionAnchor == null) {
                return unresolved("无法唯一定位目标章节，请提供更明确的章节标题或序号");
            }
            return ResolvedDocumentAnchor.builder()
                    .anchorType(DocumentAnchorType.SECTION)
                    .sectionAnchor(sectionAnchor)
                    .preview(sectionAnchor.getHeadingText())
                    .build();
        }
        if (targetsText(action)) {
            DocumentTextAnchor textAnchor = resolveTextAnchor(snapshot, spec);
            if (textAnchor == null || textAnchor.getMatchCount() == 0) {
                return unresolved("无法在文档中定位目标文本，请检查引用内容是否准确");
            }
            return ResolvedDocumentAnchor.builder()
                    .anchorType(DocumentAnchorType.TEXT)
                    .textAnchor(textAnchor)
                    .preview(textAnchor.getMatchedText())
                    .build();
        }
        // 默认：block 锚点
        DocumentBlockAnchor blockAnchor = resolveBlockAnchor(snapshot, spec);
        if (blockAnchor == null || blockAnchor.getBlockId() == null) {
            return unresolved("无法定位目标 block，请提供更明确的引用文本");
        }
        return ResolvedDocumentAnchor.builder()
                .anchorType(DocumentAnchorType.BLOCK)
                .blockAnchor(blockAnchor)
                .preview(blockAnchor.getPlainText())
                .build();
    }

    // ---- section resolution: 只消费 anchorSpec slot + snapshot 索引 ----

    private DocumentSectionAnchor resolveSectionAnchor(Artifact artifact, DocumentStructureSnapshot snapshot, DocumentAnchorSpec spec) {
        if (spec == null) return null;
        String headingBlockId = null;

        if (spec.getMatchMode() == DocumentAnchorMatchMode.BY_STRUCTURAL_ORDINAL && spec.getStructuralOrdinal() != null) {
            List<String> topLevel = snapshot.getTopLevelSequence();
            int idx = spec.getStructuralOrdinal() - 1;
            if (topLevel != null && idx >= 0 && idx < topLevel.size()) {
                headingBlockId = topLevel.get(idx);
            }
        } else if (spec.getMatchMode() == DocumentAnchorMatchMode.BY_HEADING_TITLE && spec.getHeadingTitle() != null) {
            headingBlockId = findHeadingByTitle(snapshot, spec.getHeadingTitle());
        } else if (spec.getMatchMode() == DocumentAnchorMatchMode.BY_OUTLINE_PATH && spec.getOutlinePath() != null) {
            headingBlockId = findHeadingByOutlinePath(snapshot, spec.getOutlinePath());
        }

        if (headingBlockId == null) return null;

        // 按需抓取 section 明细
        if (artifact != null) {
            String docRef = artifact.getExternalUrl() != null ? artifact.getExternalUrl() : artifact.getDocumentId();
            snapshotBuilder.fetchSectionDetail(snapshot, headingBlockId, docRef);
        }

        return buildSectionAnchor(snapshot, headingBlockId);
    }

    private String findHeadingByTitle(DocumentStructureSnapshot snapshot, String title) {
        if (snapshot.getHeadingIndex() == null || title == null) return null;
        String normalized = normalize(title);
        for (Map.Entry<String, DocumentStructureNode> entry : snapshot.getHeadingIndex().entrySet()) {
            if (normalized.equals(normalize(entry.getValue().getTitleText()))) {
                return entry.getKey();
            }
        }
        // 模糊匹配
        for (Map.Entry<String, DocumentStructureNode> entry : snapshot.getHeadingIndex().entrySet()) {
            String t = normalize(entry.getValue().getTitleText());
            if (t != null && (t.contains(normalized) || normalized.contains(t))) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String findHeadingByOutlinePath(DocumentStructureSnapshot snapshot, String outlinePath) {
        if (outlinePath == null || snapshot.getHeadingIndex() == null) return null;
        String[] parts = outlinePath.split("/");
        String last = parts[parts.length - 1].trim();
        return findHeadingByTitle(snapshot, last);
    }

    private DocumentSectionAnchor buildSectionAnchor(DocumentStructureSnapshot snapshot, String headingBlockId) {
        DocumentStructureNode node = snapshot.getHeadingIndex() == null ? null : snapshot.getHeadingIndex().get(headingBlockId);
        List<String> allBlockIds = sectionAllBlockIds(snapshot, headingBlockId);
        List<String> bodyBlockIds = allBlockIds.size() <= 1 ? List.of() : allBlockIds.subList(1, allBlockIds.size());
        int topLevelIndex = resolveTopLevelIndex(snapshot, headingBlockId);
        List<String> topLevel = snapshot.getTopLevelSequence();
        String prevId = topLevelIndex > 1 ? topLevel.get(topLevelIndex - 2) : null;
        String nextId = topLevelIndex > 0 && topLevelIndex < topLevel.size() ? topLevel.get(topLevelIndex) : null;
        return DocumentSectionAnchor.builder()
                .headingBlockId(headingBlockId)
                .headingText(node == null ? "" : node.getTitleText())
                .headingLevel(node == null ? 2 : node.getHeadingLevel())
                .bodyBlockIds(bodyBlockIds)
                .allBlockIds(allBlockIds)
                .topLevelIndex(topLevelIndex)
                .prevTopLevelSectionId(prevId)
                .nextTopLevelSectionId(nextId)
                .build();
    }

    // ---- text anchor: 基于 snapshot blockIndex，绑定 sourceBlockIds ----

    private DocumentTextAnchor resolveTextAnchor(DocumentStructureSnapshot snapshot, DocumentAnchorSpec spec) {
        String quoted = spec == null ? null : spec.getQuotedText();
        if (quoted == null || quoted.isBlank()) return null;
        List<String> sourceBlockIds = new java.util.ArrayList<>();
        int matchCount = 0;
        if (snapshot.getBlockIndex() != null) {
            for (Map.Entry<String, DocumentStructureNode> entry : snapshot.getBlockIndex().entrySet()) {
                String text = entry.getValue().getPlainText();
                if (text != null && text.contains(quoted)) {
                    matchCount++;
                    sourceBlockIds.add(entry.getKey());
                }
            }
        }
        return DocumentTextAnchor.builder()
                .matchedText(quoted)
                .matchCount(matchCount)
                .surroundingContext(quoted)
                .sourceBlockIds(List.copyOf(sourceBlockIds))
                .build();
    }

    // ---- block anchor: 基于 snapshot blockIndex ----

    private DocumentBlockAnchor resolveBlockAnchor(DocumentStructureSnapshot snapshot, DocumentAnchorSpec spec) {
        String quoted = spec == null ? null : spec.getQuotedText();
        if (quoted == null || quoted.isBlank()) return null;
        if (snapshot.getBlockIndex() == null) return null;
        List<String> ordered = snapshot.getOrderedBlockIds();
        if (ordered == null) ordered = List.copyOf(snapshot.getBlockIndex().keySet());
        for (String blockId : ordered) {
            DocumentStructureNode node = snapshot.getBlockIndex().get(blockId);
            if (isProtectedBlock(node, snapshot)) continue;
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
        return null;
    }

    // ---- media anchor: 基于 snapshot blockIndex 的 blockType 元数据 ----

    private DocumentMediaAnchor resolveMediaAnchor(DocumentStructureSnapshot snapshot, DocumentAnchorSpec spec, MediaAssetType expectedType) {
        if (snapshot.getBlockIndex() == null) return null;
        String caption = spec == null ? null : spec.getMediaCaption();
        String quoted = spec == null ? null : spec.getQuotedText();
        String hint = caption != null ? caption : quoted;
        List<String> ordered = snapshot.getOrderedBlockIds();
        if (ordered == null) ordered = List.copyOf(snapshot.getBlockIndex().keySet());
        for (int i = 0; i < ordered.size(); i++) {
            String blockId = ordered.get(i);
            DocumentStructureNode node = snapshot.getBlockIndex().get(blockId);
            if (node == null || node.getBlockType() == null) continue;
            if (!isMediaTypeMatch(node.getBlockType(), expectedType)) continue;
            if (hint != null && !hint.isBlank()) {
                String text = node.getPlainText() == null ? "" : node.getPlainText();
                if (!text.contains(hint)) continue;
            }
            String prevId = i > 0 ? ordered.get(i - 1) : null;
            String nextId = i + 1 < ordered.size() ? ordered.get(i + 1) : null;
            return DocumentMediaAnchor.builder()
                    .blockId(blockId)
                    .mediaType(expectedType)
                    .plainText(node.getPlainText())
                    .prevBlockId(prevId)
                    .nextBlockId(nextId)
                    .build();
        }
        return null;
    }

    private boolean isMediaTypeMatch(String blockType, MediaAssetType expectedType) {
        String bt = blockType.toLowerCase(Locale.ROOT);
        if (expectedType == null) return false;
        return switch (expectedType) {
            case IMAGE -> bt.contains("image") || bt.contains("media");
            case TABLE -> bt.contains("table");
            case WHITEBOARD -> bt.contains("whiteboard");
            default -> false;
        };
    }

    // ---- head metadata block ----

    private DocumentBlockAnchor resolveHeadMetadataBlock(DocumentStructureSnapshot snapshot, DocumentAnchorSpec spec) {
        if (snapshot.getBlockIndex() == null) return null;
        String firstHeadingId = snapshot.getTopLevelSequence() == null || snapshot.getTopLevelSequence().isEmpty()
                ? null : snapshot.getTopLevelSequence().get(0);
        List<String> ordered = snapshot.getOrderedBlockIds();
        if (ordered == null) ordered = List.copyOf(snapshot.getBlockIndex().keySet());
        String hint = spec == null ? null : spec.getQuotedText();
        for (int i = 0; i < ordered.size(); i++) {
            String blockId = ordered.get(i);
            if (blockId.equals(firstHeadingId)) break;
            DocumentStructureNode node = snapshot.getBlockIndex().get(blockId);
            if (isProtectedBlock(node, snapshot)) continue;
            if (node == null || node.getPlainText() == null || node.getPlainText().isBlank()) continue;
            if (hint != null && !hint.isBlank() && !containsAnyKeyword(node.getPlainText(), hint)) continue;
            String prevId = i > 0 ? ordered.get(i - 1) : null;
            String nextId = i + 1 < ordered.size() ? ordered.get(i + 1) : null;
            return DocumentBlockAnchor.builder()
                    .blockId(blockId)
                    .blockType(node.getBlockType())
                    .plainText(node.getPlainText())
                    .prevBlockId(prevId)
                    .nextBlockId(nextId)
                    .build();
        }
        return null;
    }

    // ---- helpers ----

    private DocumentSectionAnchor firstSectionAnchor(DocumentStructureSnapshot snapshot) {
        if (snapshot.getTopLevelSequence() == null || snapshot.getTopLevelSequence().isEmpty()) return null;
        String headingBlockId = snapshot.getTopLevelSequence().get(0);
        return buildSectionAnchor(snapshot, headingBlockId);
    }

    private List<String> sectionAllBlockIds(DocumentStructureSnapshot snapshot, String headingBlockId) {
        if (snapshot.getSectionBlockIds() != null) {
            List<String> ids = snapshot.getSectionBlockIds().get(headingBlockId);
            if (ids != null && !ids.isEmpty()) return ids;
        }
        return List.of(headingBlockId);
    }

    private String sectionTailBlockId(DocumentSectionAnchor sectionAnchor) {
        if (sectionAnchor == null) return null;
        List<String> all = sectionAnchor.getAllBlockIds();
        if (all == null || all.isEmpty()) return null;
        return all.get(all.size() - 1);
    }

    private int resolveTopLevelIndex(DocumentStructureSnapshot snapshot, String headingBlockId) {
        if (snapshot.getTopLevelSequence() == null) return 0;
        int idx = snapshot.getTopLevelSequence().indexOf(headingBlockId);
        if (idx >= 0) return idx + 1;
        DocumentStructureNode node = snapshot.getHeadingIndex() == null ? null : snapshot.getHeadingIndex().get(headingBlockId);
        if (node != null && node.getTopLevelAncestorId() != null) {
            idx = snapshot.getTopLevelSequence().indexOf(node.getTopLevelAncestorId());
            return idx >= 0 ? idx + 1 : 0;
        }
        return 0;
    }

    private boolean containsAnyKeyword(String text, String hint) {
        if (text == null || hint == null) return false;
        if (text.contains(hint)) return true;
        for (String kw : hint.split("[|，,\\s]+")) {
            if (kw.length() >= 2 && text.contains(kw)) return true;
        }
        // prefix match: first 2 chars of hint
        if (hint.length() >= 2 && text.contains(hint.substring(0, 2))) return true;
        return false;
    }

    private boolean isProtectedBlock(DocumentStructureNode node, DocumentStructureSnapshot snapshot) {
        if (node == null) return true;
        if ("heading".equalsIgnoreCase(node.getBlockType()) || node.getHeadingLevel() != null) return true;
        return snapshot != null && snapshot.getHeadingIndex() != null && snapshot.getHeadingIndex().containsKey(node.getBlockId());
    }

    private boolean targetsInsertAfterSection(DocumentSemanticActionType action) {
        return switch (action) {
            case INSERT_BLOCK_AFTER_ANCHOR, INSERT_IMAGE_AFTER_ANCHOR, INSERT_TABLE_AFTER_ANCHOR, INSERT_WHITEBOARD_AFTER_ANCHOR -> true;
            default -> false;
        };
    }

    private boolean targetsMedia(DocumentSemanticActionType action) {
        return switch (action) {
            case REPLACE_IMAGE, DELETE_IMAGE, REWRITE_TABLE_DATA, APPEND_TABLE_ROW, DELETE_TABLE,
                 UPDATE_WHITEBOARD_CONTENT, MOVE_MEDIA_BLOCK -> true;
            default -> false;
        };
    }

    private boolean targetsSection(DocumentSemanticActionType action) {
        return switch (action) {
            case INSERT_SECTION_BEFORE_SECTION, REWRITE_SECTION_BODY, DELETE_SECTION_BODY, DELETE_WHOLE_SECTION, MOVE_SECTION -> true;
            default -> false;
        };
    }

    private boolean targetsText(DocumentSemanticActionType action) {
        return action == DocumentSemanticActionType.REWRITE_INLINE_TEXT
                || action == DocumentSemanticActionType.DELETE_INLINE_TEXT;
    }

    private DocumentAnchorType mediaAnchorType(DocumentSemanticActionType action) {
        if (action == null) return DocumentAnchorType.MEDIA;
        return switch (action) {
            case REWRITE_TABLE_DATA, APPEND_TABLE_ROW, DELETE_TABLE -> DocumentAnchorType.TABLE;
            default -> DocumentAnchorType.MEDIA;
        };
    }

    private MediaAssetType mediaTypeFor(DocumentSemanticActionType action) {
        if (action == null) return MediaAssetType.IMAGE;
        return switch (action) {
            case REWRITE_TABLE_DATA, APPEND_TABLE_ROW, DELETE_TABLE -> MediaAssetType.TABLE;
            case UPDATE_WHITEBOARD_CONTENT -> MediaAssetType.WHITEBOARD;
            default -> MediaAssetType.IMAGE;
        };
    }

    private ResolvedDocumentAnchor unresolved(String reason) {
        return ResolvedDocumentAnchor.builder()
                .anchorType(DocumentAnchorType.UNRESOLVED)
                .preview(reason)
                .build();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
