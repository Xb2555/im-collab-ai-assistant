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
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class DocumentEditStrategyPlanner {

    public DocumentEditStrategy plan(DocumentEditIntent intent, ResolvedDocumentAnchor anchor) {
        DocumentSemanticActionType action = intent.getSemanticAction();
        return switch (action) {
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
                            .attributes(Map.of("targetHeading", anchor.getSectionAnchor() == null ? "" : anchor.getSectionAnchor().getHeadingText()))
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
                    DocumentPatchOperationType.BLOCK_INSERT_AFTER,
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
            case MOVE_BLOCK -> strategy(DocumentStrategyType.BLOCK_MOVE_AFTER, DocumentAnchorType.BLOCK,
                    DocumentPatchOperationType.BLOCK_MOVE_AFTER,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_BLOCK_INSERTED_AFTER).build(),
                    true, DocumentRiskLevel.HIGH);
            default -> strategy(DocumentStrategyType.BLOCK_INSERT_AFTER, anchor.getAnchorType(),
                    DocumentPatchOperationType.BLOCK_INSERT_AFTER,
                    ExpectedDocumentState.builder().stateType(DocumentExpectedStateType.EXPECT_BLOCK_INSERTED_AFTER).build(),
                    false, DocumentRiskLevel.MEDIUM);
        };
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
