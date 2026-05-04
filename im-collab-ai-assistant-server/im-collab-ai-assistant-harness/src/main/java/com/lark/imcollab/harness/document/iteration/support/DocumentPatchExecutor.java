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
        String lastNewBlockId = null;
        for (DocumentPatchOperation operation : plan.getPatchOperations()) {
            operation = resolveNewBlockPlaceholder(operation, lastNewBlockId);
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
            lastNewBlockId = extractFirstNewBlockId(result);
        }
        return new PatchExecutionResult(modifiedBlocks, beforeRevision, afterRevision);
    }

    private DocumentPatchOperation resolveNewBlockPlaceholder(DocumentPatchOperation operation, String lastNewBlockId) {
        if (lastNewBlockId == null || !"__new__".equals(operation.getBlockId())) {
            return operation;
        }
        return DocumentPatchOperation.builder()
                .operationType(operation.getOperationType())
                .blockId(lastNewBlockId)
                .targetBlockId(operation.getTargetBlockId())
                .startBlockId(operation.getStartBlockId())
                .endBlockId(operation.getEndBlockId())
                .oldText(operation.getOldText())
                .newContent(operation.getNewContent())
                .docFormat(operation.getDocFormat())
                .justification(operation.getJustification())
                .build();
    }

    private String extractFirstNewBlockId(LarkDocUpdateResult result) {
        if (result == null || result.getNewBlocks() == null || result.getNewBlocks().isEmpty()) {
            return null;
        }
        return result.getNewBlocks().stream()
                .map(LarkDocBlockRef::getBlockId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);
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
