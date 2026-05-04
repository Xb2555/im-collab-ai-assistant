package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.common.model.entity.TableModel;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import org.springframework.stereotype.Component;

@Component
public class InsertTableBlockStepHandler implements ExecutionStepHandler {

    private final LarkDocTool larkDocTool;

    public InsertTableBlockStepHandler(LarkDocTool larkDocTool) {
        this.larkDocTool = larkDocTool;
    }

    @Override
    public String stepType() {
        return "INSERT_TABLE_BLOCK";
    }

    @Override
    public void handle(ExecutionStep step, String docRef, RichContentExecutionContext ctx) {
        String anchorBlockId = step.getInput() == null ? null : String.valueOf(step.getInput());
        TableModel tableModel = (TableModel) ctx.get("tableModel");
        int cols = tableModel != null && tableModel.getColumns() != null ? tableModel.getColumns().size() : 2;
        int rows = tableModel != null && tableModel.getRows() != null ? tableModel.getRows().size() + 1 : 2;
        String spec = cols + "x" + rows;
        LarkDocUpdateResult result = larkDocTool.updateByCommand(
                docRef, "block_insert_after", spec, "table", anchorBlockId, null, null);
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("INSERT_TABLE_BLOCK: insert failed");
        }
        if (result.getNewBlocks() != null && !result.getNewBlocks().isEmpty()) {
            String newBlockId = result.getNewBlocks().get(0).getBlockId();
            ctx.addCreatedBlockId(newBlockId);
            ctx.put("tableBlockId", newBlockId);
        }
    }
}
