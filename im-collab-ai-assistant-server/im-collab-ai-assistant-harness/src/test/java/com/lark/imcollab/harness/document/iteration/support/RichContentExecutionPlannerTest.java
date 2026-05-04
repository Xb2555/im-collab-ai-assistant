package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.DocumentEditStrategy;
import com.lark.imcollab.common.model.entity.ExpectedDocumentState;
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
                .containsExactly("UPLOAD_IMAGE", "INSERT_IMAGE_BLOCK", "VERIFY_IMAGE_NODE");
    }
}
