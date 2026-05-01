package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.skills.lark.doc.LarkDocBlockRef;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentPatchExecutor {

    private final LarkDocTool larkDocTool;

    public DocumentPatchExecutor(LarkDocTool larkDocTool) {
        this.larkDocTool = larkDocTool;
    }

    public List<String> execute(String docRef, DocumentEditPlan plan) {
        List<String> modifiedBlocks = new ArrayList<>();
        if (plan == null || plan.getPatchOperations() == null || plan.getPatchOperations().isEmpty()) {
            return modifiedBlocks;
        }
        for (DocumentPatchOperation operation : plan.getPatchOperations()) {
            LarkDocUpdateResult result = switch (operation.getOperationType()) {
                case STR_REPLACE -> larkDocTool.updateByCommand(
                        docRef,
                        "str_replace",
                        operation.getNewContent(),
                        operation.getDocFormat(),
                        null,
                        operation.getOldText()
                );
                case BLOCK_INSERT_AFTER -> larkDocTool.updateByCommand(
                        docRef,
                        "block_insert_after",
                        operation.getNewContent(),
                        operation.getDocFormat(),
                        operation.getBlockId(),
                        null
                );
                case BLOCK_DELETE -> larkDocTool.updateByCommand(
                        docRef,
                        "block_delete",
                        null,
                        null,
                        operation.getBlockId(),
                        null
                );
                case BLOCK_REPLACE -> larkDocTool.updateByCommand(
                        docRef,
                        "block_replace",
                        operation.getNewContent(),
                        operation.getDocFormat(),
                        operation.getBlockId(),
                        null
                );
                default -> throw new IllegalStateException("Unsupported patch operation: " + operation.getOperationType());
            };
            collectModifiedBlocks(modifiedBlocks, operation, result);
        }
        return modifiedBlocks;
    }

    private void collectModifiedBlocks(
            List<String> modifiedBlocks,
            DocumentPatchOperation operation,
            LarkDocUpdateResult result
    ) {
        if (operation.getOperationType() == DocumentPatchOperationType.BLOCK_INSERT_AFTER
                && result.getNewBlocks() != null
                && !result.getNewBlocks().isEmpty()) {
            for (LarkDocBlockRef block : result.getNewBlocks()) {
                if (block.getBlockId() != null && !block.getBlockId().isBlank()) {
                    modifiedBlocks.add(block.getBlockId());
                }
            }
            return;
        }
        if (operation.getBlockId() != null && !operation.getBlockId().isBlank()) {
            modifiedBlocks.add(operation.getBlockId());
        } else if (operation.getOldText() != null && !operation.getOldText().isBlank()) {
            modifiedBlocks.add("text-match");
        }
    }
}
