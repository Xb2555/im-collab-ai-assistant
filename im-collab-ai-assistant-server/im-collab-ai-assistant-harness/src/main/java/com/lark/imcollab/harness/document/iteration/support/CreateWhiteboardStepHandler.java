package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import com.lark.imcollab.skills.lark.doc.LarkDocWriteGateway;
import org.springframework.stereotype.Component;

@Component
public class CreateWhiteboardStepHandler implements ExecutionStepHandler {

    private final LarkDocWriteGateway writeGateway;

    public CreateWhiteboardStepHandler(LarkDocWriteGateway writeGateway) {
        this.writeGateway = writeGateway;
    }

    @Override
    public String stepType() {
        return "CREATE_WHITEBOARD";
    }

    @Override
    public void handle(ExecutionStep step, String docRef, RichContentExecutionContext ctx) {
        if (!(step.getInput() instanceof RichContentExecutionPlanner.WhiteboardCreateInput input)) {
            throw new IllegalStateException("CREATE_WHITEBOARD: invalid step input");
        }
        String anchorBlockId = input.anchorBlockId();
        if (anchorBlockId == null || anchorBlockId.isBlank()) {
            throw new IllegalStateException("CREATE_WHITEBOARD: anchorBlockId is required");
        }
        LarkDocUpdateResult result = writeGateway.updateByCommand(
                docRef, "block_insert_after", "<whiteboard type=\"blank\"></whiteboard>", "markdown", anchorBlockId, null, null);
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("CREATE_WHITEBOARD: creation failed");
        }
        String whiteboardToken = firstNonBlank(
                result.getBoardTokens() != null && !result.getBoardTokens().isEmpty() ? result.getBoardTokens().get(0) : null,
                result.getNewBlocks() != null && !result.getNewBlocks().isEmpty()
                        ? firstNonBlank(result.getNewBlocks().get(0).getBlockToken(), result.getNewBlocks().get(0).getBlockId())
                        : null
        );
        if (whiteboardToken == null) {
            throw new IllegalStateException("CREATE_WHITEBOARD: no whiteboard token returned");
        }
        ctx.put("whiteboardToken", whiteboardToken);
        ctx.addCreatedAssetRef(whiteboardToken);
        if (result.getNewBlocks() != null && !result.getNewBlocks().isEmpty()) {
            String whiteboardBlockId = result.getNewBlocks().get(0).getBlockId();
            if (whiteboardBlockId != null && !whiteboardBlockId.isBlank()) {
                ctx.addCreatedBlockId(whiteboardBlockId);
                ctx.put("whiteboardBlockId", whiteboardBlockId);
            }
        }
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
