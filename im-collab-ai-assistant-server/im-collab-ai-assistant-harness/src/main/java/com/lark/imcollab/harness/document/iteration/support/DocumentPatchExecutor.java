package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.skills.lark.doc.LarkDocFetchResult;
import com.lark.imcollab.skills.lark.doc.LarkDocBlockRef;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import com.lark.imcollab.skills.lark.doc.LarkDocReadGateway;
import com.lark.imcollab.skills.lark.doc.LarkDocWriteGateway;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentPatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentPatchExecutor.class);
    private static final int APPEND_RECOVERY_MAX_ATTEMPTS = 5;
    private static final long APPEND_RECOVERY_RETRY_DELAY_MILLIS = 800L;
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^#+\\s+(.+)$");

    private final LarkDocReadGateway readGateway;
    private final LarkDocWriteGateway writeGateway;
    private final DocumentStructureParser structureParser;

    public DocumentPatchExecutor(LarkDocReadGateway readGateway, LarkDocWriteGateway writeGateway, DocumentStructureParser structureParser) {
        this.readGateway = readGateway;
        this.writeGateway = writeGateway;
        this.structureParser = structureParser;
    }

    public PatchExecutionResult execute(String docRef, DocumentEditPlan plan) {
        List<String> modifiedBlocks = new ArrayList<>();
        if (plan == null || plan.getPatchOperations() == null || plan.getPatchOperations().isEmpty()) {
            return new PatchExecutionResult(modifiedBlocks, -1L, -1L);
        }
        long beforeRevision = -1L;
        long afterRevision = -1L;
        Map<String, List<String>> runtimeGroups = new HashMap<>();
        for (DocumentPatchOperation operation : plan.getPatchOperations()) {
            log.info("DOC_ITER_PATCH_EXEC op={} blockId={} targetBlockId={} runtimeGroupKey={} docFormat={} oldText={} newContentPreview={}",
                    operation.getOperationType(),
                    operation.getBlockId(),
                    operation.getTargetBlockId(),
                    operation.getRuntimeGroupKey(),
                    operation.getDocFormat(),
                    preview(operation.getOldText()),
                    preview(operation.getNewContent()));
            if (operation.getOperationType() == DocumentPatchOperationType.BLOCK_GROUP_MOVE_AFTER) {
                List<String> groupBlockIds = resolveRuntimeGroup(operation, runtimeGroups);
                log.info("DOC_ITER_PATCH_GROUP_MOVE runtimeGroupKey={} groupBlockIds={} targetBlockId={}",
                        operation.getRuntimeGroupKey(), groupBlockIds, operation.getTargetBlockId());
                if (groupBlockIds.isEmpty()) {
                    throw new IllegalStateException("block_group_move_after 失败: 未找到可移动的新建 block group");
                }
                String targetBlockId = operation.getTargetBlockId();
                String currentTarget = targetBlockId;
                for (String blockId : groupBlockIds) {
                    LarkDocUpdateResult moveResult = writeGateway.updateByCommand(
                            docRef, "block_move_after", null, null, blockId, currentTarget, null);
                    if (!moveResult.isSuccess()) {
                        throw new IllegalStateException("block_group_move_after 失败: blockId=" + blockId + ", msg=" + moveResult.getMessage());
                    }
                    log.info("DOC_ITER_PATCH_GROUP_MOVE_RESULT blockId={} revisionId={} message={}",
                            blockId, moveResult.getRevisionId(), moveResult.getMessage());
                    long rev = moveResult.getRevisionId();
                    if (beforeRevision < 0) beforeRevision = rev;
                    afterRevision = rev;
                    currentTarget = blockId;
                    modifiedBlocks.add(blockId);
                }
                continue;
            }
            List<String> beforeAppendBlockIds = captureBlockIdsBeforeAppend(plan, docRef, operation);
            LarkDocUpdateResult result = switch (operation.getOperationType()) {
                case STR_REPLACE -> writeGateway.updateByCommand(
                        docRef, "str_replace", operation.getNewContent(),
                        operation.getDocFormat(), null, operation.getOldText(), null);
                case BLOCK_INSERT_AFTER -> writeGateway.updateByCommand(
                        docRef, "block_insert_after", operation.getNewContent(),
                        operation.getDocFormat(), operation.getBlockId(), null, null);
                case BLOCK_DELETE -> writeGateway.updateByCommand(
                        docRef, "block_delete", null, null, operation.getBlockId(), null, null);
                case BLOCK_REPLACE -> writeGateway.updateByCommand(
                        docRef, "block_replace", operation.getNewContent(),
                        operation.getDocFormat(), operation.getBlockId(), null, null);
                case APPEND -> writeGateway.updateByCommand(
                        docRef, "append", operation.getNewContent(),
                        operation.getDocFormat(), null, null, null);
                case BLOCK_MOVE_AFTER -> writeGateway.updateByCommand(
                        docRef, "block_move_after", null, null,
                        operation.getBlockId(), operation.getTargetBlockId(), null);
                default -> throw new IllegalStateException("Unsupported patch operation: " + operation.getOperationType());
            };
            if (!result.isSuccess()) {
                throw new IllegalStateException("patch 执行失败: " + operation.getOperationType() + ", msg=" + result.getMessage());
            }
            log.info("DOC_ITER_PATCH_RESULT op={} revisionId={} message={} newBlocks={} updatedBlocksCount={}",
                    operation.getOperationType(),
                    result.getRevisionId(),
                    result.getMessage(),
                    summarizeNewBlocks(result),
                    result.getUpdatedBlocksCount());
            long rev = result.getRevisionId();
            if (beforeRevision < 0) beforeRevision = rev;
            afterRevision = rev;
            collectModifiedBlocks(modifiedBlocks, operation, result);
            rememberRuntimeGroup(docRef, operation, result, beforeAppendBlockIds, runtimeGroups);
        }
        return new PatchExecutionResult(modifiedBlocks, beforeRevision, afterRevision);
    }

    private void rememberRuntimeGroup(
            String docRef,
            DocumentPatchOperation operation,
            LarkDocUpdateResult result,
            List<String> beforeAppendBlockIds,
            Map<String, List<String>> runtimeGroups
    ) {
        if (operation == null || operation.getRuntimeGroupKey() == null || operation.getRuntimeGroupKey().isBlank()) {
            return;
        }
        List<String> newBlockIds = extractNewBlockIds(result);
        if (newBlockIds.isEmpty() && operation.getOperationType() == DocumentPatchOperationType.APPEND) {
            newBlockIds = recoverAppendedBlockIds(docRef, beforeAppendBlockIds, operation);
            log.info("DOC_ITER_PATCH_APPEND_RECOVER runtimeGroupKey={} beforeAppendBlockIdsCount={} recoveredBlockIds={}",
                    operation.getRuntimeGroupKey(),
                    beforeAppendBlockIds == null ? 0 : beforeAppendBlockIds.size(),
                    newBlockIds);
        }
        if (newBlockIds.isEmpty()) {
            return;
        }
        runtimeGroups.put(operation.getRuntimeGroupKey(), List.copyOf(newBlockIds));
    }

    private List<String> resolveRuntimeGroup(DocumentPatchOperation operation, Map<String, List<String>> runtimeGroups) {
        if (operation == null || operation.getRuntimeGroupKey() == null || operation.getRuntimeGroupKey().isBlank()) {
            return List.of();
        }
        return runtimeGroups.getOrDefault(operation.getRuntimeGroupKey(), List.of());
    }

    private List<String> extractNewBlockIds(LarkDocUpdateResult result) {
        if (result == null || result.getNewBlocks() == null) return List.of();
        return result.getNewBlocks().stream()
                .map(LarkDocBlockRef::getBlockId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    private List<String> summarizeNewBlocks(LarkDocUpdateResult result) {
        if (result == null || result.getNewBlocks() == null || result.getNewBlocks().isEmpty()) {
            return List.of();
        }
        return result.getNewBlocks().stream()
                .map(block -> block.getBlockId() + ":" + block.getBlockType())
                .toList();
    }

    private String preview(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 120) + "...";
    }

    private List<String> captureBlockIdsBeforeAppend(DocumentEditPlan plan, String docRef, DocumentPatchOperation operation) {
        if (operation == null || operation.getOperationType() != DocumentPatchOperationType.APPEND) {
            return List.of();
        }
        if (operation.getRuntimeGroupKey() == null || operation.getRuntimeGroupKey().isBlank()) {
            return List.of();
        }
        if (plan != null && plan.getStructureSnapshot() != null
                && plan.getStructureSnapshot().getOrderedBlockIds() != null
                && !plan.getStructureSnapshot().getOrderedBlockIds().isEmpty()) {
            return List.copyOf(plan.getStructureSnapshot().getOrderedBlockIds());
        }
        try {
            return fetchOrderedBlockIds(docRef);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<String> recoverAppendedBlockIds(String docRef, List<String> beforeAppendBlockIds, DocumentPatchOperation operation) {
        if (beforeAppendBlockIds == null || beforeAppendBlockIds.isEmpty()) {
            return List.of();
        }
        String appendedHeading = extractInsertedHeading(operation == null ? null : operation.getNewContent());
        for (int attempt = 1; attempt <= APPEND_RECOVERY_MAX_ATTEMPTS; attempt++) {
            try {
                List<String> appended = recoverAppendedBlockIdsFromOutline(docRef, beforeAppendBlockIds, appendedHeading);
                log.info("DOC_ITER_PATCH_APPEND_RECOVER_RETRY docRef={} attempt={} appendedHeading={} appendedBlockIds={}",
                        docRef,
                        attempt,
                        appendedHeading,
                        appended);
                if (!appended.isEmpty()) {
                    return appended;
                }
            } catch (RuntimeException ignored) {
                log.info("DOC_ITER_PATCH_APPEND_RECOVER_RETRY docRef={} attempt={} appendedHeading={} appendedBlockIds=[]",
                        docRef,
                        attempt,
                        appendedHeading);
            }
            if (attempt < APPEND_RECOVERY_MAX_ATTEMPTS) {
                sleepQuietly(APPEND_RECOVERY_RETRY_DELAY_MILLIS);
            }
        }
        return List.of();
    }

    private List<String> recoverAppendedBlockIdsFromOutline(String docRef, List<String> beforeAppendBlockIds, String appendedHeading) {
        if (appendedHeading == null || appendedHeading.isBlank()) {
            return List.of();
        }
        LarkDocFetchResult outline = readGateway.fetchDocOutline(docRef);
        if (outline == null || outline.getContent() == null || outline.getContent().isBlank()) {
            return List.of();
        }
        Set<String> existing = new LinkedHashSet<>(beforeAppendBlockIds);
        List<DocumentStructureParser.HeadingBlock> headings = structureParser.parseHeadings(outline.getContent());
        DocumentStructureParser.HeadingBlock appendedHeadingBlock = headings.stream()
                .filter(Objects::nonNull)
                .filter(heading -> !existing.contains(heading.getBlockId()))
                .filter(heading -> normalize(heading.getText()).equals(normalize(appendedHeading)))
                .findFirst()
                .orElse(null);
        if (appendedHeadingBlock == null) {
            return List.of();
        }
        LarkDocFetchResult section = readGateway.fetchDocSection(docRef, appendedHeadingBlock.getBlockId(), "with-ids");
        if (section == null || section.getContent() == null || section.getContent().isBlank()) {
            return List.of(appendedHeadingBlock.getBlockId());
        }
        List<String> sectionBlockIds = structureParser.parseBlockIds(section.getContent()).stream()
                .filter(id -> !existing.contains(id))
                .toList();
        if (!sectionBlockIds.isEmpty()) {
            return sectionBlockIds;
        }
        return List.of(appendedHeadingBlock.getBlockId());
    }

    private List<String> fetchOrderedBlockIds(String docRef) {
        LarkDocFetchResult fullDoc = readGateway.fetchDocFull(docRef, "with-ids");
        if (fullDoc == null || fullDoc.getContent() == null || fullDoc.getContent().isBlank()) {
            return List.of();
        }
        return structureParser.parseBlockIds(fullDoc.getContent());
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private String extractInsertedHeading(String newContent) {
        if (newContent == null || newContent.isBlank()) {
            return null;
        }
        String unwrapped = structureParser.unwrapMarkdownFragment(newContent);
        for (String line : unwrapped.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(trimmed);
            if (matcher.matches()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private void collectModifiedBlocks(List<String> modifiedBlocks, DocumentPatchOperation operation, LarkDocUpdateResult result) {
        if (result.getNewBlocks() != null && !result.getNewBlocks().isEmpty()) {
            for (LarkDocBlockRef block : result.getNewBlocks()) {
                if (block.getBlockId() != null && !block.getBlockId().isBlank()) {
                    modifiedBlocks.add(block.getBlockId());
                }
            }
            return;
        }
        switch (operation.getOperationType()) {
            case BLOCK_INSERT_AFTER -> modifiedBlocks.add("insert-after:" + operation.getBlockId());
            case APPEND -> modifiedBlocks.add("append");
            default -> {
                if (operation.getBlockId() != null && !operation.getBlockId().isBlank()) {
                    modifiedBlocks.add(operation.getBlockId());
                } else if (operation.getOldText() != null && !operation.getOldText().isBlank()) {
                    modifiedBlocks.add("text-match");
                }
            }
        }
    }

    @Getter
    @AllArgsConstructor
    public static class PatchExecutionResult {
        private final List<String> modifiedBlocks;
        private final long beforeRevision;
        private final long afterRevision;
    }
}
