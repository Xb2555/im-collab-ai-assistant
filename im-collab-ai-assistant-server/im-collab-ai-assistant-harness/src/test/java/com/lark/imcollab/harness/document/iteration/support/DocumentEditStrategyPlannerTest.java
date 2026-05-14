package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.enums.DocumentAnchorType;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import com.lark.imcollab.common.model.enums.DocumentStrategyType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentEditStrategyPlannerTest {

    @Test
    void whiteboardInsertDoesNotRequireApproval() {
        DocumentEditStrategyPlanner planner = new DocumentEditStrategyPlanner();

        var strategy = planner.plan(
                DocumentEditIntent.builder()
                        .semanticAction(DocumentSemanticActionType.INSERT_WHITEBOARD_AFTER_ANCHOR)
                        .build(),
                ResolvedDocumentAnchor.builder()
                        .anchorType(DocumentAnchorType.BLOCK)
                        .preview("2.1 三日经典游")
                        .build()
        );

        assertThat(strategy.getStrategyType()).isEqualTo(DocumentStrategyType.WHITEBOARD_INSERT_AFTER);
        assertThat(strategy.getExpectedState().getStateType()).isEqualTo(DocumentExpectedStateType.EXPECT_WHITEBOARD_NODE_PRESENT);
        assertThat(strategy.isRequiresApproval()).isFalse();
        assertThat(strategy.getRiskLevel()).isEqualTo(DocumentRiskLevel.MEDIUM);
    }
}
