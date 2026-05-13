package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.DocumentEditStrategy;
import com.lark.imcollab.common.model.entity.ExecutionPlan;
import com.lark.imcollab.common.model.entity.ExecutionStep;
import com.lark.imcollab.common.model.entity.ResolvedAsset;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
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
        if (intent == null || intent.getSemanticAction() == null || asset == null) {
            return null;
        }
        List<ExecutionStep> steps = new ArrayList<>();
        switch (intent.getSemanticAction()) {
            case INSERT_IMAGE_AFTER_ANCHOR -> buildImageInsertSteps(steps, asset, anchor);
            case INSERT_TABLE_AFTER_ANCHOR -> buildTableInsertSteps(steps, asset, anchor);
            case REWRITE_TABLE_DATA, APPEND_TABLE_ROW -> buildTableRewriteSteps(steps, asset, anchor);
            case INSERT_WHITEBOARD_AFTER_ANCHOR -> buildWhiteboardInsertSteps(steps, asset, anchor);
            case UPDATE_WHITEBOARD_CONTENT -> buildWhiteboardUpdateSteps(steps, asset, anchor);
            default -> {
                return null;
            }
        }
        return ExecutionPlan.builder()
                .steps(steps)
                .requiresApproval(strategy.isRequiresApproval())
                .rollbackPolicy("ABORT_ON_FAILURE")
                .expectedState(strategy.getExpectedState() == null ? null : strategy.getExpectedState().getStateType())
                .build();
    }

    private void buildImageInsertSteps(List<ExecutionStep> steps, ResolvedAsset asset, ResolvedDocumentAnchor anchor) {
        steps.add(step("UPLOAD_IMAGE", "lark_drive_upload", asset.getAssetRef()));
        steps.add(step("INSERT_IMAGE_BLOCK", "lark_doc_block_insert_after",
                resolveInsertionBlockId(anchor)));
    }

    private void buildTableInsertSteps(List<ExecutionStep> steps, ResolvedAsset asset, ResolvedDocumentAnchor anchor) {
        steps.add(step("RESOLVE_TABLE_SCHEMA", "table_asset_resolver", asset.getTableModel()));
        steps.add(step("INSERT_TABLE_BLOCK", "lark_doc_block_insert_after",
                resolveInsertionBlockId(anchor)));
        steps.add(step("WRITE_TABLE_DATA", "lark_doc_table_write", asset.getTableModel()));
    }

    private void buildTableRewriteSteps(List<ExecutionStep> steps, ResolvedAsset asset, ResolvedDocumentAnchor anchor) {
        steps.add(step("RESOLVE_TABLE_SCHEMA", "table_asset_resolver", asset.getTableModel()));
        steps.add(step("WRITE_TABLE_DATA", "lark_doc_table_write", resolveTargetBlockId(anchor)));
    }

    private void buildWhiteboardUpdateSteps(List<ExecutionStep> steps, ResolvedAsset asset, ResolvedDocumentAnchor anchor) {
        steps.add(step("UPDATE_WHITEBOARD", "lark_doc_whiteboard_update", resolveWhiteboardUpdateInput(asset, anchor)));
    }

    private void buildWhiteboardInsertSteps(List<ExecutionStep> steps, ResolvedAsset asset, ResolvedDocumentAnchor anchor) {
        steps.add(step("CREATE_WHITEBOARD", "lark_doc_create_whiteboard",
                new WhiteboardCreateInput(resolveInsertionBlockId(anchor))));
        steps.add(step("UPDATE_WHITEBOARD", "lark_doc_whiteboard_update", resolveCreateThenUpdateWhiteboardInput(asset)));
    }

    private String resolveInsertionBlockId(ResolvedDocumentAnchor anchor) {
        if (anchor == null) {
            return null;
        }
        if (anchor.getInsertionBlockId() != null) {
            return anchor.getInsertionBlockId();
        }
        if (anchor.getBlockAnchor() != null) {
            return anchor.getBlockAnchor().getBlockId();
        }
        if (anchor.getSectionAnchor() != null) {
            return anchor.getSectionAnchor().getHeadingBlockId();
        }
        return null;
    }

    private String resolveTargetBlockId(ResolvedDocumentAnchor anchor) {
        if (anchor == null || anchor.getMediaAnchor() == null) {
            return null;
        }
        return anchor.getMediaAnchor().getBlockId();
    }

    private WhiteboardUpdateInput resolveWhiteboardUpdateInput(ResolvedAsset asset, ResolvedDocumentAnchor anchor) {
        return new WhiteboardUpdateInput(resolveTargetBlockId(anchor), asset.getAssetRef());
    }

    private WhiteboardUpdateInput resolveCreateThenUpdateWhiteboardInput(ResolvedAsset asset) {
        return new WhiteboardUpdateInput(null, asset.getAssetRef());
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

    public record WhiteboardCreateInput(String anchorBlockId) {}
    public record WhiteboardUpdateInput(String blockId, String dsl) {}
}
