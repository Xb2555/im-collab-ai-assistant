package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import com.lark.imcollab.skills.lark.doc.LarkDocWriteGateway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateWhiteboardStepHandlerTest {

    @Test
    void handleUsesWhiteboardUpdateCommand() {
        LarkDocWriteGateway writeGateway = mock(LarkDocWriteGateway.class);
        when(writeGateway.updateByCommand(eq("doc123"), eq("whiteboard_update"), eq("graph TD;A-->B"),
                eq("whiteboard"), eq("wb-1"), isNull(), isNull()))
                .thenReturn(LarkDocUpdateResult.builder().success(true).build());
        UpdateWhiteboardStepHandler handler = new UpdateWhiteboardStepHandler(writeGateway);
        RichContentExecutionContext ctx = new RichContentExecutionContext();

        handler.handle(ExecutionStep.builder()
                .stepType("UPDATE_WHITEBOARD")
                .input(new RichContentExecutionPlanner.WhiteboardUpdateInput("wb-1", "graph TD;A-->B"))
                .build(), "doc123", ctx);

        verify(writeGateway).updateByCommand("doc123", "whiteboard_update", "graph TD;A-->B",
                "whiteboard", "wb-1", null, null);
        assertThat(ctx.getString("whiteboardBlockId")).isEqualTo("wb-1");
    }
}
