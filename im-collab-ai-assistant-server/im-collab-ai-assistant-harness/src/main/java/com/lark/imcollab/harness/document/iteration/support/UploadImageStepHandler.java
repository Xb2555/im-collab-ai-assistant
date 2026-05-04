package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.common.model.entity.TableModel;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

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
        // 上传图片到飞书云空间，得到 file_token
        LarkDocUpdateResult result = larkDocTool.updateByCommand(docRef, "upload_image", assetRef, null, null, null, null);
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("UPLOAD_IMAGE: upload failed");
        }
        String fileToken = result.getNewBlocks() != null && !result.getNewBlocks().isEmpty()
                ? result.getNewBlocks().get(0).getBlockId() : assetRef;
        ctx.put("uploadedFileToken", fileToken);
        ctx.addCreatedAssetRef(fileToken);
    }
}
