package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import org.springframework.stereotype.Component;

@Component
public class InsertImageBlockStepHandler implements ExecutionStepHandler {

    private final LarkDocTool larkDocTool;

    public InsertImageBlockStepHandler(LarkDocTool larkDocTool) {
        this.larkDocTool = larkDocTool;
    }

    @Override
    public String stepType() {
        return "INSERT_IMAGE_BLOCK";
    }

    @Override
    public void handle(ExecutionStep step, String docRef, RichContentExecutionContext ctx) {
        String anchorBlockId = step.getInput() == null ? null : String.valueOf(step.getInput());
        String fileToken = ctx.getString("uploadedFileToken");
        if (fileToken == null || fileToken.isBlank()) {
            fileToken = anchorBlockId;
        }
        LarkDocUpdateResult result = larkDocTool.updateByCommand(
                docRef, "block_insert_after", fileToken, "image", anchorBlockId, null, null);
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("INSERT_IMAGE_BLOCK: insert failed");
        }
        if (result.getNewBlocks() != null && !result.getNewBlocks().isEmpty()) {
            String newBlockId = result.getNewBlocks().get(0).getBlockId();
            ctx.addCreatedBlockId(newBlockId);
            ctx.put("imageBlockId", newBlockId);
        }
    }
}
