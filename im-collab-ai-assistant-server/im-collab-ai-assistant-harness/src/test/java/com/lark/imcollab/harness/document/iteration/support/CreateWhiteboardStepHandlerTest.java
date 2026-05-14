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

class CreateWhiteboardStepHandlerTest {

    @Test
    void handleStoresBlockTokenForSubsequentWhiteboardUpdate() {
        LarkDocWriteGateway writeGateway = mock(LarkDocWriteGateway.class);
        when(writeGateway.updateByCommand(eq("doc123"), eq("block_insert_after"),
                eq("<whiteboard type=\"blank\"></whiteboard>"), eq("markdown"), eq("anchor-1"), isNull(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder()
                        .success(true)
                        .boardTokens(List.of("board-token-1"))
                        .newBlocks(List.of(LarkDocBlockRef.builder()
                                .blockId("blk-1")
                                .blockToken("wb-token-1")
                                .blockType("whiteboard")
                                .build()))
                        .build());
        CreateWhiteboardStepHandler handler = new CreateWhiteboardStepHandler(writeGateway);
        RichContentExecutionContext ctx = new RichContentExecutionContext();

        handler.handle(ExecutionStep.builder()
                .stepType("CREATE_WHITEBOARD")
                .input(new RichContentExecutionPlanner.WhiteboardCreateInput("anchor-1"))
                .build(), "doc123", ctx);

        verify(writeGateway).updateByCommand("doc123", "block_insert_after",
                "<whiteboard type=\"blank\"></whiteboard>", "markdown", "anchor-1", null, null);
        assertThat(ctx.getString("whiteboardToken")).isEqualTo("board-token-1");
        assertThat(ctx.getString("whiteboardBlockId")).isEqualTo("blk-1");
    }
}
