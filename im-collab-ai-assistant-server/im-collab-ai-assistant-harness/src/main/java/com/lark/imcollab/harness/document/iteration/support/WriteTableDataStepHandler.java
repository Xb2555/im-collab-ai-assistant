package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.common.model.entity.TableModel;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import com.lark.imcollab.skills.lark.doc.LarkDocWriteGateway;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class WriteTableDataStepHandler implements ExecutionStepHandler {

    private final LarkDocWriteGateway writeGateway;

    public WriteTableDataStepHandler(LarkDocWriteGateway writeGateway) {
        this.writeGateway = writeGateway;
    }

    @Override
    public String stepType() {
        return "WRITE_TABLE_DATA";
    }

    @Override
    public void handle(ExecutionStep step, String docRef, RichContentExecutionContext ctx) {
        String tableBlockId = ctx.getString("tableBlockId");
        if ((tableBlockId == null || tableBlockId.isBlank()) && step.getInput() != null) {
            tableBlockId = String.valueOf(step.getInput());
        }
        if (tableBlockId == null || tableBlockId.isBlank()) {
            throw new IllegalStateException("WRITE_TABLE_DATA: tableBlockId not found in context");
        }
        TableModel tableModel = (TableModel) ctx.get("tableModel");
        if (tableModel == null) {
            return;
        }
        String csv = buildCsv(tableModel);
        LarkDocUpdateResult result = writeGateway.updateByCommand(
                docRef, "table_write", csv, "csv", tableBlockId, null, null);
        if (result == null || !result.isSuccess()) {
            throw new IllegalStateException("WRITE_TABLE_DATA: write failed");
        }
    }

    private String buildCsv(TableModel tableModel) {
        List<List<String>> allRows = new ArrayList<>();
        if (tableModel.getColumns() != null) {
            allRows.add(tableModel.getColumns());
        }
        if (tableModel.getRows() != null) {
            allRows.addAll(tableModel.getRows());
        }
        return allRows.stream()
                .map(row -> row.stream().map(cell -> "\"" + (cell == null ? "" : cell.replace("\"", "\"\"")) + "\"")
                        .collect(Collectors.joining(",")))
                .collect(Collectors.joining("\n"));
    }
}
