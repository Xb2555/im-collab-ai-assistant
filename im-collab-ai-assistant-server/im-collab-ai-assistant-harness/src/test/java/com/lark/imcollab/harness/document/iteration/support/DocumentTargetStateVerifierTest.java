package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentEditStrategy;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.common.model.entity.DocumentStructureNode;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.entity.ExpectedDocumentState;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.enums.DocumentAnchorType;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.common.model.enums.DocumentStrategyType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTargetStateVerifierTest {

    @Test
    void verifyNewSectionBeforeUsesHeadingOrderInsteadOfOldBlockIdentity() {
        DocumentTargetStateVerifier verifier = new DocumentTargetStateVerifier();

        assertThatCode(() -> verifier.verify(planForBeforeInsert("1.3 基础设施与服务能力", "1.2 游客偏好分析"),
                snapshot(List.of(
                        heading("old-target", "1.3 基础设施与服务能力"),
                        heading("old-next", "1.4 后续规划")
                )),
                snapshot(List.of(
                        heading("new-inserted", "1.2 游客偏好分析"),
                        heading("new-target", "1.3 基础设施与服务能力"),
                        heading("new-next", "1.4 后续规划")
                ))))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyNewSectionBeforeFailsWhenInsertedHeadingIsStillAfterTarget() {
        DocumentTargetStateVerifier verifier = new DocumentTargetStateVerifier();

        assertThatThrownBy(() -> verifier.verify(planForBeforeInsert("1.3 基础设施与服务能力", "1.2 游客偏好分析"),
                snapshot(List.of(
                        heading("old-target", "1.3 基础设施与服务能力"),
                        heading("old-next", "1.4 后续规划")
                )),
                snapshot(List.of(
                        heading("new-target", "1.3 基础设施与服务能力"),
                        heading("new-inserted", "1.2 游客偏好分析"),
                        heading("new-next", "1.4 后续规划")
                ))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未出现在目标章节之前");
    }

    @Test
    void verifyBlockInsertedAfterAcceptsInsertedHeadingAndFragmentedBody() {
        DocumentTargetStateVerifier verifier = new DocumentTargetStateVerifier();

        DocumentEditPlan plan = DocumentEditPlan.builder()
                .resolvedAnchor(ResolvedDocumentAnchor.builder().anchorType(DocumentAnchorType.BLOCK).build())
                .expectedState(ExpectedDocumentState.builder()
                        .stateType(DocumentExpectedStateType.EXPECT_BLOCK_INSERTED_AFTER)
                        .build())
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                        .blockId("heading-1-3")
                        .newContent("""
                                ### 1.4 游客偏好分析

                                基于样本数据，游客更偏好夜游与文化体验。
                                """)
                        .build()))
                .build();

        DocumentStructureSnapshot before = snapshot(List.of(
                heading("heading-1-3", "1.3 客源市场结构")
        ));
        DocumentStructureSnapshot after = snapshotWithParagraphs(
                List.of(
                        heading("heading-1-3", "1.3 客源市场结构"),
                        heading("heading-1-4", "1.4 游客偏好分析")
                ),
                List.of(
                        paragraph("body-1", "基于样本数据"),
                        paragraph("body-2", "游客更偏好夜游与文化体验。")
                )
        );

        assertThatCode(() -> verifier.verify(plan, before, after)).doesNotThrowAnyException();
    }

    private DocumentEditPlan planForBeforeInsert(String targetHeading, String insertedHeading) {
        return DocumentEditPlan.builder()
                .resolvedAnchor(ResolvedDocumentAnchor.builder().anchorType(DocumentAnchorType.SECTION).build())
                .strategy(DocumentEditStrategy.builder()
                        .strategyType(DocumentStrategyType.CONTROLLED_BEFORE_SECTION_INSERT)
                        .expectedState(ExpectedDocumentState.builder()
                                .stateType(DocumentExpectedStateType.EXPECT_NEW_SECTION_BEFORE_TARGET_SECTION)
                                .attributes(Map.of(
                                        "targetHeadingText", targetHeading,
                                        "newSectionHeadingText", insertedHeading
                                ))
                                .build())
                        .build())
                .expectedState(ExpectedDocumentState.builder()
                        .stateType(DocumentExpectedStateType.EXPECT_NEW_SECTION_BEFORE_TARGET_SECTION)
                        .attributes(Map.of(
                                "targetHeadingText", targetHeading,
                                "newSectionHeadingText", insertedHeading
                        ))
                        .build())
                .build();
    }

    private DocumentStructureSnapshot snapshot(List<DocumentStructureNode> headings) {
        Map<String, DocumentStructureNode> headingIndex = new LinkedHashMap<>();
        Map<String, DocumentStructureNode> blockIndex = new LinkedHashMap<>();
        List<String> topLevel = headings.stream().map(DocumentStructureNode::getBlockId).toList();
        for (DocumentStructureNode heading : headings) {
            headingIndex.put(heading.getBlockId(), heading);
            blockIndex.put(heading.getBlockId(), heading);
        }
        return DocumentStructureSnapshot.builder()
                .headingIndex(headingIndex)
                .blockIndex(blockIndex)
                .topLevelSequence(topLevel)
                .orderedBlockIds(topLevel)
                .build();
    }

    private DocumentStructureNode heading(String blockId, String title) {
        return DocumentStructureNode.builder()
                .blockId(blockId)
                .blockType("heading")
                .headingLevel(2)
                .titleText(title)
                .plainText(title)
                .topLevelAncestorId(blockId)
                .build();
    }

    private DocumentStructureSnapshot snapshotWithParagraphs(List<DocumentStructureNode> headings, List<DocumentStructureNode> paragraphs) {
        Map<String, DocumentStructureNode> headingIndex = new LinkedHashMap<>();
        Map<String, DocumentStructureNode> blockIndex = new LinkedHashMap<>();
        List<String> ordered = new java.util.ArrayList<>();
        List<String> topLevel = headings.stream().map(DocumentStructureNode::getBlockId).toList();
        for (DocumentStructureNode heading : headings) {
            headingIndex.put(heading.getBlockId(), heading);
            blockIndex.put(heading.getBlockId(), heading);
            ordered.add(heading.getBlockId());
        }
        for (DocumentStructureNode paragraph : paragraphs) {
            blockIndex.put(paragraph.getBlockId(), paragraph);
            ordered.add(paragraph.getBlockId());
        }
        return DocumentStructureSnapshot.builder()
                .headingIndex(headingIndex)
                .blockIndex(blockIndex)
                .topLevelSequence(topLevel)
                .orderedBlockIds(ordered)
                .build();
    }

    private DocumentStructureNode paragraph(String blockId, String text) {
        return DocumentStructureNode.builder()
                .blockId(blockId)
                .blockType("p")
                .plainText(text)
                .build();
    }
}
