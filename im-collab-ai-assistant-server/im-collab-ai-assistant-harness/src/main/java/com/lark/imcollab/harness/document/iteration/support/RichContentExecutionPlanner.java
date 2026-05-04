package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.DocumentEditStrategy;
import com.lark.imcollab.common.model.entity.ExecutionPlan;
import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.common.model.entity.ResolvedAsset;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.enums.MediaAssetType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RichContentExecutionPlanner {

    public ExecutionPlan plan(
            DocumentEditIntent intent,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy,
            ResolvedAsset asset
    ) {
        if (asset == null) {
            return null;
        }
        List<ExecutionStep> steps = new ArrayList<>();
        switch (asset.getAssetType()) {
            case IMAGE -> buildImageSteps(steps, asset, anchor);
            case TABLE -> buildTableSteps(steps, asset, anchor);
            case WHITEBOARD -> buildWhiteboardSteps(steps, asset, anchor);
            default -> steps.add(step("UNSUPPORTED", "unsupported_asset_type", null));
        }
        return ExecutionPlan.builder()
                .steps(steps)
                .requiresApproval(strategy.isRequiresApproval())
                .rollbackPolicy("ABORT_ON_FAILURE")
                .expectedState(strategy.getExpectedState() == null ? null : strategy.getExpectedState().getStateType())
                .build();
    }

    private void buildImageSteps(List<ExecutionStep> steps, ResolvedAsset asset, ResolvedDocumentAnchor anchor) {
        if (asset.isRequiresUpload()) {
            steps.add(step("UPLOAD_IMAGE", "lark_drive_upload", asset.getAssetRef()));
        }
        steps.add(step("INSERT_IMAGE_BLOCK", "lark_doc_block_insert_after",
                anchor.getInsertionBlockId() != null ? anchor.getInsertionBlockId()
                        : anchor.getBlockAnchor() != null ? anchor.getBlockAnchor().getBlockId() : null));
        steps.add(step("VERIFY_IMAGE_NODE", "snapshot_verify", MediaAssetType.IMAGE.name()));
    }

    private void buildTableSteps(List<ExecutionStep> steps, ResolvedAsset asset, ResolvedDocumentAnchor anchor) {
        steps.add(step("RESOLVE_TABLE_SCHEMA", "table_asset_resolver", asset.getTableModel()));
        steps.add(step("INSERT_TABLE_BLOCK", "lark_doc_block_insert_after",
                anchor.getInsertionBlockId() != null ? anchor.getInsertionBlockId()
                        : anchor.getBlockAnchor() != null ? anchor.getBlockAnchor().getBlockId() : null));
        steps.add(step("WRITE_TABLE_DATA", "lark_doc_table_write", asset.getTableModel()));
        steps.add(step("VERIFY_TABLE_NODE", "snapshot_verify", MediaAssetType.TABLE.name()));
    }

    private void buildWhiteboardSteps(List<ExecutionStep> steps, ResolvedAsset asset, ResolvedDocumentAnchor anchor) {
        steps.add(step("CREATE_WHITEBOARD", "lark_whiteboard_create", asset.getAssetRef()));
        steps.add(step("INSERT_WHITEBOARD_REF", "lark_doc_block_insert_after",
                anchor.getInsertionBlockId() != null ? anchor.getInsertionBlockId()
                        : anchor.getBlockAnchor() != null ? anchor.getBlockAnchor().getBlockId() : null));
        steps.add(step("VERIFY_WHITEBOARD_NODE", "snapshot_verify", MediaAssetType.WHITEBOARD.name()));
    }

    private ExecutionStep step(String stepType, String toolBinding, Object input) {
        return ExecutionStep.builder()
                .stepId(stepType.toLowerCase())
                .stepType(stepType)
                .toolBinding(toolBinding)
                .input(input)
                .failureMode("ABORT")
                .build();
    }
}
