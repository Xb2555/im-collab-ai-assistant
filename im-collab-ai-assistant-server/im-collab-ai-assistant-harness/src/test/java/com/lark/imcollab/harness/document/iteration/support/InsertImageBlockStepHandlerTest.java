package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.skills.lark.doc.LarkDocBlockRef;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import com.lark.imcollab.skills.lark.doc.LarkDocWriteGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InsertImageBlockStepHandlerTest {

    @Test
    void handleEmbedsCaptionIntoInsertedMarkdownImage() {
        LarkDocWriteGateway writeGateway = mock(LarkDocWriteGateway.class);
        when(writeGateway.updateByCommand(eq("doc123"), eq("block_insert_after"), eq("![东方明珠](file-token-1)"),
                eq("markdown"), eq("anchor-1"), isNull(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder()
                        .success(true)
                        .newBlocks(List.of(LarkDocBlockRef.builder().blockId("img-block-1").build()))
                        .build());
        InsertImageBlockStepHandler handler = new InsertImageBlockStepHandler(writeGateway);
        RichContentExecutionContext ctx = new RichContentExecutionContext();
        ctx.put("uploadedFileToken", "file-token-1");
        ctx.put("imageCaption", "东方明珠");

        handler.handle(ExecutionStep.builder()
                .stepType("INSERT_IMAGE_BLOCK")
                .input("anchor-1")
                .build(), "doc123", ctx);

        verify(writeGateway).updateByCommand("doc123", "block_insert_after", "![东方明珠](file-token-1)",
                "markdown", "anchor-1", null, null);
        assertThat(ctx.getCreatedBlockIds()).containsExactly("img-block-1");
        assertThat(ctx.getString("imageBlockId")).isEqualTo("img-block-1");
    }

    @Test
    void handlePrefersActualImageBlockWhenNewBlocksContainContainer() {
        LarkDocWriteGateway writeGateway = mock(LarkDocWriteGateway.class);
        when(writeGateway.updateByCommand(eq("doc123"), eq("block_insert_after"), eq("![东方明珠](file-token-1)"),
                eq("markdown"), eq("anchor-1"), isNull(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder()
                        .success(true)
                        .newBlocks(List.of(
                                LarkDocBlockRef.builder().blockId("paragraph-1").blockType("paragraph").build(),
                                LarkDocBlockRef.builder().blockId("img-block-1").blockType("image").build()))
                        .build());
        InsertImageBlockStepHandler handler = new InsertImageBlockStepHandler(writeGateway);
        RichContentExecutionContext ctx = new RichContentExecutionContext();
        ctx.put("uploadedFileToken", "file-token-1");
        ctx.put("imageCaption", "东方明珠");

        handler.handle(ExecutionStep.builder()
                .stepType("INSERT_IMAGE_BLOCK")
                .input("anchor-1")
                .build(), "doc123", ctx);

        assertThat(ctx.getCreatedBlockIds()).containsExactly("img-block-1");
        assertThat(ctx.getString("imageBlockId")).isEqualTo("img-block-1");
    }
}
