package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import com.lark.imcollab.skills.lark.doc.LarkDocWriteGateway;
import org.springframework.stereotype.Component;

@Component
public class UpdateWhiteboardStepHandler implements ExecutionStepHandler {

    private final LarkDocWriteGateway writeGateway;

    public UpdateWhiteboardStepHandler(LarkDocWriteGateway writeGateway) {
        this.writeGateway = writeGateway;
    }

    @Override
    public String stepType() {
        return "UPDATE_WHITEBOARD";
    }

    @Override
    public void handle(ExecutionStep step, String docRef, RichContentExecutionContext ctx) {
        if (!(step.getInput() instanceof RichContentExecutionPlanner.WhiteboardUpdateInput input)) {
            throw new IllegalStateException("UPDATE_WHITEBOARD: invalid step input");
        }
        if (input.dsl() == null || input.dsl().isBlank()) {
            throw new IllegalStateException("UPDATE_WHITEBOARD: dsl is required");
        }
        String blockId = input.blockId();
        if (blockId == null || blockId.isBlank()) {
            blockId = ctx.getString("whiteboardToken");
        }
        if (blockId == null || blockId.isBlank()) {
            throw new IllegalStateException("UPDATE_WHITEBOARD: blockId is required");
        }
        LarkDocUpdateResult result = writeGateway.updateWhiteboard(blockId, input.dsl(), "mermaid");
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("UPDATE_WHITEBOARD: update failed");
        }
        ctx.put("whiteboardBlockId", blockId);
    }
}
