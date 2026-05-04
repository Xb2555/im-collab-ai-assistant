package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadImageStepHandlerTest {

    @Test
    void handlePassesAssetRefThroughWithoutCallingDocUpdate() {
        UploadImageStepHandler handler = new UploadImageStepHandler();
        RichContentExecutionContext ctx = new RichContentExecutionContext();
        ExecutionStep step = ExecutionStep.builder()
                .stepType("UPLOAD_IMAGE")
                .input("file_token_or_attachment_ref_1")
                .build();

        handler.handle(step, "doc123", ctx);

        assertThat(ctx.getString("uploadedFileToken")).isEqualTo("file_token_or_attachment_ref_1");
        assertThat(ctx.getCreatedAssetRefs()).containsExactly("file_token_or_attachment_ref_1");
    }

    @Test
    void handleFailsFastWhenAssetRefMissing() {
        UploadImageStepHandler handler = new UploadImageStepHandler();

        assertThatThrownBy(() -> handler.handle(ExecutionStep.builder().stepType("UPLOAD_IMAGE").build(), "doc123", new RichContentExecutionContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assetRef is missing");
    }
}
