package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentMediaAnchor;
import com.lark.imcollab.common.model.entity.DocumentStructureNode;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.entity.ExpectedDocumentState;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.entity.RichContentExecutionResult;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

class RichContentTargetStateVerifierTest {

    private final RichContentTargetStateVerifier verifier = new RichContentTargetStateVerifier();

    @Test
    void imageInsertPassesWhenCreatedBlockAppearsAfterAnchor() {
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .semanticAction(DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR)
                .resolvedAnchor(ResolvedDocumentAnchor.builder().insertionBlockId("anchor-1").build())
                .expectedState(ExpectedDocumentState.builder()
                        .stateType(DocumentExpectedStateType.EXPECT_IMAGE_NODE_PRESENT)
                        .build())
                .build();
        RichContentExecutionResult result = RichContentExecutionResult.builder()
                .createdBlockIds(List.of("img-1"))
                .build();
        DocumentStructureSnapshot after = DocumentStructureSnapshot.builder()
                .revisionId(2L)
                .blockIndex(Map.of(
                        "anchor-1", node("anchor-1", "text"),
                        "img-1", node("img-1", "image")))
                .blockOrderIndex(Map.of("anchor-1", 1, "img-1", 2))
                .build();

        assertThatCode(() -> verifier.verify(plan, result, null, after)).doesNotThrowAnyException();
    }

    @Test
    void imageInsertPassesWhenCreatedBlockIsContainerButNewImageExistsAfterAnchor() {
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .semanticAction(DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR)
                .resolvedAnchor(ResolvedDocumentAnchor.builder().insertionBlockId("anchor-1").build())
                .expectedState(ExpectedDocumentState.builder()
                        .stateType(DocumentExpectedStateType.EXPECT_IMAGE_NODE_PRESENT)
                        .build())
                .build();
        RichContentExecutionResult result = RichContentExecutionResult.builder()
                .createdBlockIds(List.of("paragraph-1"))
                .build();
        DocumentStructureSnapshot before = DocumentStructureSnapshot.builder()
                .revisionId(1L)
                .blockIndex(Map.of("anchor-1", node("anchor-1", "text")))
                .blockOrderIndex(Map.of("anchor-1", 1))
                .build();
        DocumentStructureSnapshot after = DocumentStructureSnapshot.builder()
                .revisionId(2L)
                .blockIndex(Map.of(
                        "anchor-1", node("anchor-1", "text"),
                        "paragraph-1", node("paragraph-1", "paragraph"),
                        "img-1", node("img-1", "image")))
                .blockOrderIndex(Map.of("anchor-1", 1, "paragraph-1", 2, "img-1", 3))
                .build();

        assertThatCode(() -> verifier.verify(plan, result, before, after)).doesNotThrowAnyException();
    }

    @Test
    void imageInsertPassesWhenOnlyRevisionAdvancesAndOutlineHasNoImageNode() {
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .semanticAction(DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR)
                .resolvedAnchor(ResolvedDocumentAnchor.builder().insertionBlockId("anchor-1").build())
                .expectedState(ExpectedDocumentState.builder()
                        .stateType(DocumentExpectedStateType.EXPECT_IMAGE_NODE_PRESENT)
                        .build())
                .build();
        RichContentExecutionResult result = RichContentExecutionResult.builder()
                .createdBlockIds(List.of())
                .beforeRevision(8L)
                .afterRevision(9L)
                .build();
        DocumentStructureSnapshot before = DocumentStructureSnapshot.builder()
                .revisionId(8L)
                .blockIndex(Map.of("anchor-1", node("anchor-1", "heading")))
                .blockOrderIndex(Map.of("anchor-1", 1))
                .build();
        DocumentStructureSnapshot after = DocumentStructureSnapshot.builder()
                .revisionId(9L)
                .blockIndex(Map.of("anchor-1", node("anchor-1", "heading")))
                .blockOrderIndex(Map.of("anchor-1", 1))
                .build();

        assertThatCode(() -> verifier.verify(plan, result, before, after)).doesNotThrowAnyException();
    }

    @Test
    void rewriteTablePassesWithoutCreatedBlockIds() {
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .semanticAction(DocumentSemanticActionType.REWRITE_TABLE_DATA)
                .resolvedAnchor(ResolvedDocumentAnchor.builder()
                        .mediaAnchor(DocumentMediaAnchor.builder().blockId("table-1").build())
                        .build())
                .expectedState(ExpectedDocumentState.builder()
                        .stateType(DocumentExpectedStateType.EXPECT_TABLE_NODE_PRESENT)
                        .build())
                .build();
        DocumentStructureSnapshot after = DocumentStructureSnapshot.builder()
                .revisionId(3L)
                .blockIndex(Map.of("table-1", node("table-1", "table")))
                .build();

        assertThatCode(() -> verifier.verify(plan, RichContentExecutionResult.builder().build(), null, after))
                .doesNotThrowAnyException();
    }

    @Test
    void whiteboardUpdatePassesWhenNodeRemainsAndRevisionAdvances() {
        DocumentEditPlan plan = DocumentEditPlan.builder()
                .semanticAction(DocumentSemanticActionType.UPDATE_WHITEBOARD_CONTENT)
                .resolvedAnchor(ResolvedDocumentAnchor.builder()
                        .mediaAnchor(DocumentMediaAnchor.builder().blockId("wb-1").build())
                        .build())
                .expectedState(ExpectedDocumentState.builder()
                        .stateType(DocumentExpectedStateType.EXPECT_WHITEBOARD_NODE_PRESENT)
                        .build())
                .build();
        DocumentStructureSnapshot before = DocumentStructureSnapshot.builder()
                .revisionId(5L)
                .blockIndex(Map.of("wb-1", node("wb-1", "whiteboard")))
                .build();
        DocumentStructureSnapshot after = DocumentStructureSnapshot.builder()
                .revisionId(6L)
                .blockIndex(Map.of("wb-1", node("wb-1", "whiteboard")))
                .build();

        assertThatCode(() -> verifier.verify(plan, RichContentExecutionResult.builder().build(), before, after))
                .doesNotThrowAnyException();
    }

    private DocumentStructureNode node(String blockId, String blockType) {
        return DocumentStructureNode.builder().blockId(blockId).blockType(blockType).build();
    }
}
