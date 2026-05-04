package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.skills.lark.doc.LarkDocBlockRef;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentPatchExecutor {

    private final LarkDocTool larkDocTool;

    public DocumentPatchExecutor(LarkDocTool larkDocTool) {
        this.larkDocTool = larkDocTool;
    }

    public PatchExecutionResult execute(String docRef, DocumentEditPlan plan) {
        List<String> modifiedBlocks = new ArrayList<>();
        if (plan == null || plan.getPatchOperations() == null || plan.getPatchOperations().isEmpty()) {
            return new PatchExecutionResult(modifiedBlocks, -1L, -1L);
        }
        long beforeRevision = -1L;
        long afterRevision = -1L;
        List<String> lastNewBlockIds = List.of();
        for (DocumentPatchOperation operation : plan.getPatchOperations()) {
            operation = resolveNewBlockPlaceholder(operation, lastNewBlockIds);
            LarkDocUpdateResult result = switch (operation.getOperationType()) {
                case STR_REPLACE -> larkDocTool.updateByCommand(
                        docRef, "str_replace", operation.getNewContent(),
                        operation.getDocFormat(), null, operation.getOldText(), null);
                case BLOCK_INSERT_AFTER -> larkDocTool.updateByCommand(
                        docRef, "block_insert_after", operation.getNewContent(),
                        operation.getDocFormat(), operation.getBlockId(), null, null);
                case BLOCK_DELETE -> larkDocTool.updateByCommand(
                        docRef, "block_delete", null, null, operation.getBlockId(), null, null);
                case BLOCK_REPLACE -> larkDocTool.updateByCommand(
                        docRef, "block_replace", operation.getNewContent(),
                        operation.getDocFormat(), operation.getBlockId(), null, null);
                case APPEND -> larkDocTool.updateByCommand(
                        docRef, "append", operation.getNewContent(),
                        operation.getDocFormat(), null, null, null);
                case BLOCK_MOVE_AFTER -> larkDocTool.updateByCommand(
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
            lastNewBlockIds = extractNewBlockIds(result);
        }
        return new PatchExecutionResult(modifiedBlocks, beforeRevision, afterRevision);
    }

    private DocumentPatchOperation resolveNewBlockPlaceholder(DocumentPatchOperation operation, List<String> lastNewBlockIds) {
        if (!"__new__".equals(operation.getBlockId()) || lastNewBlockIds.isEmpty()) {
            return operation;
        }
        // 用第一个新 block id 作为代理（多 block section 移动的已知限制）
        return DocumentPatchOperation.builder()
                .operationType(operation.getOperationType())
                .blockId(lastNewBlockIds.get(0))
                .targetBlockId(operation.getTargetBlockId())
                .startBlockId(operation.getStartBlockId())
                .endBlockId(operation.getEndBlockId())
                .oldText(operation.getOldText())
                .newContent(operation.getNewContent())
                .docFormat(operation.getDocFormat())
                .justification(operation.getJustification())
                .build();
    }

    private List<String> extractNewBlockIds(LarkDocUpdateResult result) {
        if (result == null || result.getNewBlocks() == null) return List.of();
        return result.getNewBlocks().stream()
                .map(LarkDocBlockRef::getBlockId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
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
