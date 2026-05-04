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
        String altText = firstNonBlank(ctx.getString("imageAltText"), ctx.getString("imageCaption"), "image");
        LarkDocUpdateResult result = larkDocTool.updateByCommand(
                docRef, "block_insert_after", toMarkdownImage(altText, fileToken), "markdown", anchorBlockId, null, null);
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("INSERT_IMAGE_BLOCK: insert failed");
        }
        String newBlockId = null;
        if (result.getNewBlocks() != null && !result.getNewBlocks().isEmpty()) {
            newBlockId = result.getNewBlocks().get(0).getBlockId();
        } else if (result.getBoardTokens() != null && !result.getBoardTokens().isEmpty()) {
            newBlockId = result.getBoardTokens().get(0);
        }
        if (newBlockId != null && !newBlockId.isBlank()) {
            ctx.addCreatedBlockId(newBlockId);
            ctx.put("imageBlockId", newBlockId);
        }
    }

    private String toMarkdownImage(String altText, String fileToken) {
        return "![" + sanitizeAltText(altText) + "](" + fileToken + ")";
    }

    private String sanitizeAltText(String value) {
        if (value == null || value.isBlank()) {
            return "image";
        }
        return value.replace("]", "\\]");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
