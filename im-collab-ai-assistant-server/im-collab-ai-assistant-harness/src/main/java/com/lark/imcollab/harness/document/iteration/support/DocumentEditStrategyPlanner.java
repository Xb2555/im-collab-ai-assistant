package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.DocumentEditStrategy;
import com.lark.imcollab.common.model.entity.ExpectedDocumentState;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.enums.DocumentAnchorType;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import com.lark.imcollab.common.model.enums.DocumentStrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DocumentEditStrategyPlanner {

    private static final Logger log = LoggerFactory.getLogger(DocumentEditStrategyPlanner.class);

    public DocumentEditStrategy plan(DocumentEditIntent intent, ResolvedDocumentAnchor anchor) {
        DocumentSemanticActionType action = intent.getSemanticAction();
        DocumentEditStrategy strategy = switch (action) {
            case EXPLAIN_ONLY -> strategy(DocumentStrategyType.EXPLAIN_ONLY, anchor.getAnchorType(), null,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_NO_CHANGE).build(),
                    false, DocumentRiskLevel.LOW);
            case INSERT_METADATA_AT_DOCUMENT_HEAD -> strategy(DocumentStrategyType.CONTROLLED_HEAD_INSERT, DocumentAnchorType.DOCUMENT_HEAD,
                    DocumentPatchOperationType.BLOCK_INSERT_AFTER,
                    ExpectedDocumentState.builder()
                            .stateType(DocumentExpectedStateType.EXPECT_METADATA_BLOCK_PRESENT_AT_HEAD)
                            .attributes(Map.of("position", "head"))
                            .build(),
                    true, DocumentRiskLevel.HIGH);
            case INSERT_SECTION_BEFORE_SECTION -> strategy(DocumentStrategyType.CONTROLLED_BEFORE_SECTION_INSERT, DocumentAnchorType.SECTION,
                    DocumentPatchOperationType.BLOCK_INSERT_AFTER,
                    ExpectedDocumentState.builder()
                            .stateType(DocumentExpectedStateType.EXPECT_NEW_SECTION_BEFORE_TARGET_SECTION)
                            .attributes(Map.of(
                                    "targetHeadingText", anchor.getSectionAnchor() == null ? "" : anchor.getSectionAnchor().getHeadingText(),
                                    "targetHeadingId", anchor.getSectionAnchor() == null ? "" : anchor.getSectionAnchor().getHeadingBlockId()))
                            .build(),
                    true, DocumentRiskLevel.HIGH);
            case APPEND_SECTION_TO_DOCUMENT_END -> strategy(DocumentStrategyType.APPEND, DocumentAnchorType.DOCUMENT_TAIL,
                    DocumentPatchOperationType.APPEND,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_CONTENT_APPENDED).build(),
                    false, DocumentRiskLevel.MEDIUM);
            case REWRITE_METADATA_AT_DOCUMENT_HEAD -> strategy(DocumentStrategyType.CONTROLLED_HEAD_REWRITE, DocumentAnchorType.BLOCK,
                    DocumentPatchOperationType.BLOCK_REPLACE,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_TEXT_REPLACED).build(),
                    true, DocumentRiskLevel.HIGH);
            case REWRITE_SECTION_BODY -> strategy(DocumentStrategyType.BLOCK_REPLACE, DocumentAnchorType.SECTION,
                    DocumentPatchOperationType.BLOCK_REPLACE,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_TEXT_REPLACED).build(),
                    false, DocumentRiskLevel.MEDIUM);
            case DELETE_METADATA_AT_DOCUMENT_HEAD -> strategy(DocumentStrategyType.CONTROLLED_HEAD_DELETE, DocumentAnchorType.BLOCK,
                    DocumentPatchOperationType.BLOCK_DELETE,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_BLOCK_REMOVED).build(),
                    true, DocumentRiskLevel.HIGH);
            case DELETE_SECTION_BODY -> strategy(DocumentStrategyType.BLOCK_DELETE, DocumentAnchorType.SECTION,
                    DocumentPatchOperationType.BLOCK_DELETE,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_SECTION_BODY_REMOVED).build(),
                    true, DocumentRiskLevel.HIGH);
            case DELETE_WHOLE_SECTION -> strategy(DocumentStrategyType.BLOCK_DELETE, DocumentAnchorType.SECTION,
                    DocumentPatchOperationType.BLOCK_DELETE,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_SECTION_REMOVED).build(),
                    true, DocumentRiskLevel.HIGH);
            case DELETE_BLOCK -> strategy(DocumentStrategyType.BLOCK_DELETE, DocumentAnchorType.BLOCK,
                    DocumentPatchOperationType.BLOCK_DELETE,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_BLOCK_REMOVED).build(),
                    true, DocumentRiskLevel.HIGH);
            case REWRITE_INLINE_TEXT -> strategy(DocumentStrategyType.TEXT_REPLACE, DocumentAnchorType.TEXT,
                    DocumentPatchOperationType.STR_REPLACE,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_TEXT_REPLACED).build(),
                    false, DocumentRiskLevel.MEDIUM);
            case MOVE_SECTION -> strategy(DocumentStrategyType.BLOCK_MOVE_AFTER, DocumentAnchorType.SECTION,
                    DocumentPatchOperationType.BLOCK_MOVE_AFTER,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_BLOCK_INSERTED_AFTER).build(),
                    true, DocumentRiskLevel.HIGH);
            case MOVE_BLOCK, MOVE_MEDIA_BLOCK -> strategy(DocumentStrategyType.BLOCK_MOVE_AFTER, DocumentAnchorType.BLOCK,
                    DocumentPatchOperationType.BLOCK_MOVE_AFTER,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_BLOCK_INSERTED_AFTER).build(),
                    true, DocumentRiskLevel.HIGH);
            case INSERT_IMAGE_AFTER_ANCHOR -> strategy(DocumentStrategyType.MEDIA_INSERT_AFTER, DocumentAnchorType.BLOCK,
                    DocumentPatchOperationType.BLOCK_INSERT_AFTER,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_IMAGE_NODE_PRESENT).build(),
                    false, DocumentRiskLevel.MEDIUM);
            case REPLACE_IMAGE -> strategy(DocumentStrategyType.BLOCK_REPLACE, DocumentAnchorType.MEDIA,
                    DocumentPatchOperationType.BLOCK_REPLACE,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_IMAGE_NODE_PRESENT).build(),
                    true, DocumentRiskLevel.HIGH);
            case DELETE_IMAGE -> strategy(DocumentStrategyType.BLOCK_DELETE, DocumentAnchorType.MEDIA,
                    DocumentPatchOperationType.BLOCK_DELETE,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_MEDIA_NODE_REMOVED).build(),
                    true, DocumentRiskLevel.HIGH);
            case INSERT_TABLE_AFTER_ANCHOR -> strategy(DocumentStrategyType.TABLE_INSERT_AFTER, DocumentAnchorType.BLOCK,
                    DocumentPatchOperationType.BLOCK_INSERT_AFTER,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_TABLE_NODE_PRESENT).build(),
                    false, DocumentRiskLevel.MEDIUM);
            case REWRITE_TABLE_DATA, APPEND_TABLE_ROW -> strategy(DocumentStrategyType.TABLE_DATA_REWRITE, DocumentAnchorType.TABLE,
                    DocumentPatchOperationType.BLOCK_REPLACE,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_TABLE_NODE_PRESENT).build(),
                    true, DocumentRiskLevel.HIGH);
            case DELETE_TABLE -> strategy(DocumentStrategyType.BLOCK_DELETE, DocumentAnchorType.TABLE,
                    DocumentPatchOperationType.BLOCK_DELETE,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_MEDIA_NODE_REMOVED).build(),
                    true, DocumentRiskLevel.HIGH);
            case INSERT_WHITEBOARD_AFTER_ANCHOR -> strategy(DocumentStrategyType.WHITEBOARD_INSERT_AFTER, DocumentAnchorType.BLOCK,
                    DocumentPatchOperationType.BLOCK_INSERT_AFTER,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_WHITEBOARD_NODE_PRESENT).build(),
                    false, DocumentRiskLevel.MEDIUM);
            case UPDATE_WHITEBOARD_CONTENT -> strategy(DocumentStrategyType.WHITEBOARD_INSERT_AFTER, DocumentAnchorType.MEDIA,
                    DocumentPatchOperationType.BLOCK_REPLACE,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_WHITEBOARD_NODE_PRESENT).build(),
                    true, DocumentRiskLevel.HIGH);
            case RELAYOUT_SECTION -> strategy(DocumentStrategyType.LAYOUT_REORDER, DocumentAnchorType.SECTION,
                    DocumentPatchOperationType.BLOCK_MOVE_AFTER,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_LAYOUT_REORDERED).build(),
                    true, DocumentRiskLevel.HIGH);
            case CONVERT_TEXT_TO_TABLE -> strategy(DocumentStrategyType.CONVERT_TO_TABLE, DocumentAnchorType.BLOCK,
                    DocumentPatchOperationType.BLOCK_INSERT_AFTER,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_TABLE_NODE_PRESENT).build(),
                    true, DocumentRiskLevel.HIGH);
            case CONVERT_TEXT_TO_IMAGE_CARD -> strategy(DocumentStrategyType.CONVERT_TO_IMAGE_CARD, DocumentAnchorType.BLOCK,
                    DocumentPatchOperationType.BLOCK_INSERT_AFTER,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_IMAGE_NODE_PRESENT).build(),
                    true, DocumentRiskLevel.HIGH);
            default -> strategy(DocumentStrategyType.BLOCK_INSERT_AFTER, anchor.getAnchorType(),
                    DocumentPatchOperationType.BLOCK_INSERT_AFTER,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_BLOCK_INSERTED_AFTER).build(),
                    false, DocumentRiskLevel.MEDIUM);
        };
        log.info("DOC_ITER_STRATEGY action={} resolvedAnchorType={} strategyType={} patchFamily={} expectedState={} requiresApproval={} riskLevel={}",
                action,
                anchor == null ? null : anchor.getAnchorType(),
                strategy.getStrategyType(),
                strategy.getPatchFamily(),
                strategy.getExpectedState() == null ? null : strategy.getExpectedState().getStateType(),
                strategy.isRequiresApproval(),
                strategy.getRiskLevel());
        return strategy;
    }

    private DocumentEditStrategy strategy(
            DocumentStrategyType strategyType,
            DocumentAnchorType anchorType,
            DocumentPatchOperationType patchFamily,
            ExpectedDocumentState expectedState,
            boolean requiresApproval,
            DocumentRiskLevel riskLevel
    ) {
        return DocumentEditStrategy.builder()
                .strategyType(strategyType)
                .anchorType(anchorType)
                .patchFamily(patchFamily)
                .expectedState(expectedState)
                .requiresApproval(requiresApproval)
                .riskLevel(riskLevel)
                .build();
    }
}
