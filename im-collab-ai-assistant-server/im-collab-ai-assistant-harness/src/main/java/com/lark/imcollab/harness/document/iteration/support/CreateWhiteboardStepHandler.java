package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import org.springframework.stereotype.Component;

@Component
public class CreateWhiteboardStepHandler implements ExecutionStepHandler {

    private final LarkDocTool larkDocTool;

    public CreateWhiteboardStepHandler(LarkDocTool larkDocTool) {
        this.larkDocTool = larkDocTool;
    }

    @Override
    public String stepType() {
        return "CREATE_WHITEBOARD";
    }

    @Override
    public void handle(ExecutionStep step, String docRef, RichContentExecutionContext ctx) {
        String dsl = step.getInput() == null ? null : String.valueOf(step.getInput());
        LarkDocUpdateResult result = larkDocTool.updateByCommand(
                docRef, "create_whiteboard", dsl, null, null, null, null);
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("CREATE_WHITEBOARD: creation failed");
        }
        String whiteboardToken = result.getNewBlocks() != null && !result.getNewBlocks().isEmpty()
                ? result.getNewBlocks().get(0).getBlockId() : null;
        if (whiteboardToken == null) {
            throw new IllegalStateException("CREATE_WHITEBOARD: no whiteboard token returned");
        }
        ctx.put("whiteboardToken", whiteboardToken);
        ctx.addCreatedAssetRef(whiteboardToken);
    }
}
