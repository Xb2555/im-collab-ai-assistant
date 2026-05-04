package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import org.springframework.stereotype.Component;

@Component
public class InsertWhiteboardRefStepHandler implements ExecutionStepHandler {

    private final LarkDocTool larkDocTool;

    public InsertWhiteboardRefStepHandler(LarkDocTool larkDocTool) {
        this.larkDocTool = larkDocTool;
    }

    @Override
    public String stepType() {
        return "INSERT_WHITEBOARD_REF";
    }

    @Override
    public void handle(ExecutionStep step, String docRef, RichContentExecutionContext ctx) {
        String anchorBlockId = step.getInput() == null ? null : String.valueOf(step.getInput());
        String whiteboardToken = ctx.getString("whiteboardToken");
        if (whiteboardToken == null || whiteboardToken.isBlank()) {
            throw new IllegalStateException("INSERT_WHITEBOARD_REF: whiteboardToken not found in context");
        }
        LarkDocUpdateResult result = larkDocTool.updateByCommand(
                docRef, "block_insert_after", whiteboardToken, "whiteboard", anchorBlockId, null, null);
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("INSERT_WHITEBOARD_REF: insert failed");
        }
        if (result.getNewBlocks() != null && !result.getNewBlocks().isEmpty()) {
            String newBlockId = result.getNewBlocks().get(0).getBlockId();
            ctx.addCreatedBlockId(newBlockId);
            ctx.put("whiteboardBlockId", newBlockId);
        }
    }
}
