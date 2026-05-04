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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DocumentPatchExecutor {

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
            if (operation.getOperationType() == DocumentPatchOperationType.BLOCK_GROUP_MOVE_AFTER) {
                List<String> groupBlockIds = resolveRuntimeGroup(operation, runtimeGroups);
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
                    long rev = moveResult.getRevisionId();
                    if (beforeRevision < 0) beforeRevision = rev;
                    afterRevision = rev;
                    currentTarget = blockId;
                    modifiedBlocks.add(blockId);
                }
                continue;
            }
            List<String> beforeAppendBlockIds = captureBlockIdsBeforeAppend(docRef, operation);
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
            newBlockIds = recoverAppendedBlockIds(docRef, beforeAppendBlockIds);
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

    private List<String> captureBlockIdsBeforeAppend(String docRef, DocumentPatchOperation operation) {
        if (operation == null || operation.getOperationType() != DocumentPatchOperationType.APPEND) {
            return List.of();
        }
        if (operation.getRuntimeGroupKey() == null || operation.getRuntimeGroupKey().isBlank()) {
            return List.of();
        }
        try {
            return fetchOrderedBlockIds(docRef);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<String> recoverAppendedBlockIds(String docRef, List<String> beforeAppendBlockIds) {
        if (beforeAppendBlockIds == null || beforeAppendBlockIds.isEmpty()) {
            return List.of();
        }
        try {
            List<String> afterAppendBlockIds = fetchOrderedBlockIds(docRef);
            if (afterAppendBlockIds.isEmpty()) {
                return List.of();
            }
            Set<String> existing = new LinkedHashSet<>(beforeAppendBlockIds);
            List<String> appended = new ArrayList<>();
            for (String blockId : afterAppendBlockIds) {
                if (!existing.contains(blockId)) {
                    appended.add(blockId);
                }
            }
            return appended;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<String> fetchOrderedBlockIds(String docRef) {
        LarkDocFetchResult fullDoc = readGateway.fetchDocFull(docRef, "with-ids");
        if (fullDoc == null || fullDoc.getContent() == null || fullDoc.getContent().isBlank()) {
            return List.of();
        }
        return structureParser.parseBlockIds(fullDoc.getContent());
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
