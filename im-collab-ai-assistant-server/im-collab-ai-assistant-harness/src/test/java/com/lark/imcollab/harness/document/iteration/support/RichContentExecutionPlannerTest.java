package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.DocumentEditStrategy;
import com.lark.imcollab.common.model.entity.ExpectedDocumentState;
import com.lark.imcollab.common.model.entity.DocumentMediaAnchor;
import com.lark.imcollab.common.model.entity.ResolvedAsset;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import com.lark.imcollab.common.model.enums.DocumentStrategyType;
import com.lark.imcollab.common.model.enums.MediaAssetType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RichContentExecutionPlannerTest {

    @Test
    void imagePlanDoesNotEmitDetachedCaptionUpdateStep() {
        RichContentExecutionPlanner planner = new RichContentExecutionPlanner();

        var executionPlan = planner.plan(
                DocumentEditIntent.builder()
                        .semanticAction(DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR)
                        .build(),
                ResolvedDocumentAnchor.builder()
                        .insertionBlockId("anchor-1")
                        .build(),
                DocumentEditStrategy.builder()
                        .strategyType(DocumentStrategyType.MEDIA_INSERT_AFTER)
                        .expectedState(ExpectedDocumentState.builder()
                                .stateType(DocumentExpectedStateType.EXPECT_IMAGE_NODE_PRESENT)
                                .build())
                        .build(),
                ResolvedAsset.builder()
                        .assetType(MediaAssetType.IMAGE)
                        .assetRef("https://example.com/image.png")
                        .caption("东方明珠")
                        .requiresUpload(true)
                        .build()
        );

        assertThat(executionPlan.getSteps())
                .extracting(step -> step.getStepType())
                .containsExactly("UPLOAD_IMAGE", "INSERT_IMAGE_BLOCK");
    }

    @Test
    void imagePlanStillUsesUploadAndInsertWhenAssetIsDirectUrl() {
        RichContentExecutionPlanner planner = new RichContentExecutionPlanner();

        var executionPlan = planner.plan(
                DocumentEditIntent.builder()
                        .semanticAction(DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR)
                        .build(),
                ResolvedDocumentAnchor.builder()
                        .insertionBlockId("anchor-1")
                        .build(),
                DocumentEditStrategy.builder()
                        .strategyType(DocumentStrategyType.MEDIA_INSERT_AFTER)
                        .expectedState(ExpectedDocumentState.builder()
                                .stateType(DocumentExpectedStateType.EXPECT_IMAGE_NODE_PRESENT)
                                .build())
                        .build(),
                ResolvedAsset.builder()
                        .assetType(MediaAssetType.IMAGE)
                        .assetRef("https://example.com/image.png")
                        .requiresUpload(false)
                        .build()
        );

        assertThat(executionPlan.getSteps())
                .extracting(step -> step.getStepType())
                .containsExactly("UPLOAD_IMAGE", "INSERT_IMAGE_BLOCK");
    }

    @Test
    void whiteboardUpdateUsesUpdateStepInsteadOfCreate() {
        RichContentExecutionPlanner planner = new RichContentExecutionPlanner();

        var executionPlan = planner.plan(
                DocumentEditIntent.builder()
                        .semanticAction(DocumentSemanticActionType.UPDATE_WHITEBOARD_CONTENT)
                        .build(),
                ResolvedDocumentAnchor.builder()
                        .mediaAnchor(DocumentMediaAnchor.builder().blockId("wb-1").build())
                        .build(),
                DocumentEditStrategy.builder()
                        .strategyType(DocumentStrategyType.WHITEBOARD_INSERT_AFTER)
                        .expectedState(ExpectedDocumentState.builder()
                                .stateType(DocumentExpectedStateType.EXPECT_WHITEBOARD_NODE_PRESENT)
                                .build())
                        .build(),
                ResolvedAsset.builder()
                        .assetType(MediaAssetType.WHITEBOARD)
                        .assetRef("graph TD;A-->B")
                        .build()
        );

        assertThat(executionPlan.getSteps())
                .extracting(step -> step.getStepType())
                .containsExactly("UPDATE_WHITEBOARD");
    }

    @Test
    void whiteboardInsertCreatesUpdatesAndInsertsReference() {
        RichContentExecutionPlanner planner = new RichContentExecutionPlanner();

        var executionPlan = planner.plan(
                DocumentEditIntent.builder()
                        .semanticAction(DocumentSemanticActionType.INSERT_WHITEBOARD_AFTER_ANCHOR)
                        .build(),
                ResolvedDocumentAnchor.builder()
                        .insertionBlockId("anchor-9")
                        .build(),
                DocumentEditStrategy.builder()
                        .strategyType(DocumentStrategyType.WHITEBOARD_INSERT_AFTER)
                        .expectedState(ExpectedDocumentState.builder()
                                .stateType(DocumentExpectedStateType.EXPECT_WHITEBOARD_NODE_PRESENT)
                                .build())
                        .build(),
                ResolvedAsset.builder()
                        .assetType(MediaAssetType.WHITEBOARD)
                        .assetRef("flowchart TD;A-->B")
                        .build()
        );

        assertThat(executionPlan.getSteps())
                .extracting(step -> step.getStepType())
                .containsExactly("CREATE_WHITEBOARD", "UPDATE_WHITEBOARD");
        assertThat(executionPlan.getSteps().get(0).getInput())
                .isEqualTo(new RichContentExecutionPlanner.WhiteboardCreateInput("anchor-9"));
    }

    @Test
    void rewriteTableTargetsExistingTableBlock() {
        RichContentExecutionPlanner planner = new RichContentExecutionPlanner();

        var executionPlan = planner.plan(
                DocumentEditIntent.builder()
                        .semanticAction(DocumentSemanticActionType.REWRITE_TABLE_DATA)
                        .build(),
                ResolvedDocumentAnchor.builder()
                        .mediaAnchor(DocumentMediaAnchor.builder().blockId("table-1").build())
                        .build(),
                DocumentEditStrategy.builder()
                        .strategyType(DocumentStrategyType.TABLE_DATA_REWRITE)
                        .expectedState(ExpectedDocumentState.builder()
                                .stateType(DocumentExpectedStateType.EXPECT_TABLE_NODE_PRESENT)
                                .build())
                        .build(),
                ResolvedAsset.builder()
                        .assetType(MediaAssetType.TABLE)
                        .tableModel(com.lark.imcollab.common.model.entity.TableModel.builder()
                                .columns(java.util.List.of("A"))
                                .rows(java.util.List.of(java.util.List.of("1")))
                                .build())
                        .build()
        );

        assertThat(executionPlan.getSteps())
                .extracting(step -> step.getStepType())
                .containsExactly("RESOLVE_TABLE_SCHEMA", "WRITE_TABLE_DATA");
        assertThat(executionPlan.getSteps().get(1).getInput()).isEqualTo("table-1");
    }
}
