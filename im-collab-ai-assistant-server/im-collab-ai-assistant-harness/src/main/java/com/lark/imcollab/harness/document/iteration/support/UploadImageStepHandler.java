package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import org.springframework.stereotype.Component;

@Component
public class UploadImageStepHandler implements ExecutionStepHandler {

    private final LarkDocTool larkDocTool;

    public UploadImageStepHandler(LarkDocTool larkDocTool) {
        this.larkDocTool = larkDocTool;
    }

    @Override
    public String stepType() {
        return "UPLOAD_IMAGE";
    }

    @Override
    public void handle(ExecutionStep step, String docRef, RichContentExecutionContext ctx) {
        String assetRef = step.getInput() == null ? null : String.valueOf(step.getInput());
        if (assetRef == null || assetRef.isBlank()) {
            throw new IllegalStateException("UPLOAD_IMAGE: assetRef is missing");
        }
        // 这里不再走 docs +update --command upload_image；该命令在当前 CLI 上不兼容。
        // 先把资产引用透传给后续插图步骤，若上游已提供 file_token 则可直接使用。
        ctx.put("uploadedFileToken", assetRef);
        ctx.addCreatedAssetRef(assetRef);
    }
}
