package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentStructureNode;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.entity.RichContentExecutionResult;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class RichContentTargetStateVerifier {

    private static final Logger log = LoggerFactory.getLogger(RichContentTargetStateVerifier.class);

    public void verify(DocumentEditPlan plan, RichContentExecutionResult executionResult,
                       DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        if (plan == null || plan.getExpectedState() == null || plan.getExpectedState().getStateType() == null) {
            return;
        }
        DocumentExpectedStateType stateType = plan.getExpectedState().getStateType();
        switch (stateType) {
            case EXPECT_IMAGE_NODE_PRESENT, EXPECT_TABLE_NODE_PRESENT, EXPECT_WHITEBOARD_NODE_PRESENT ->
                    verifyMediaNodePresent(plan, executionResult, before, after);
            case EXPECT_MEDIA_NODE_REMOVED -> verifyMediaNodeRemoved(before, after, plan);
            case EXPECT_LAYOUT_REORDERED -> verifyLayoutReordered(before, after);
            default -> {}
        }
    }

    private void verifyMediaNodePresent(
            DocumentEditPlan plan,
            RichContentExecutionResult executionResult,
            DocumentStructureSnapshot before,
            DocumentStructureSnapshot after
    ) {
        if (after == null || after.getBlockIndex() == null) {
            throw new IllegalStateException("目标状态校验失败：after snapshot 为空");
        }
        if (plan == null || plan.getSemanticAction() == null) {
            return;
        }
        switch (plan.getSemanticAction()) {
            case INSERT_IMAGE_AFTER_ANCHOR -> verifyInsertedNodePresent(plan, executionResult, before, after, "image");
            case INSERT_TABLE_AFTER_ANCHOR -> verifyInsertedNodePresent(plan, executionResult, before, after, "table");
            case REWRITE_TABLE_DATA, APPEND_TABLE_ROW -> verifyExistingTargetPresent(plan, after, "table");
            case UPDATE_WHITEBOARD_CONTENT -> verifyWhiteboardUpdated(plan, before, after);
            default -> {}
        }
    }

    private void verifyInsertedNodePresent(
            DocumentEditPlan plan,
            RichContentExecutionResult executionResult,
            DocumentStructureSnapshot before,
            DocumentStructureSnapshot after,
            String expectedTypeToken
    ) {
        List<String> createdBlockIds = executionResult == null ? List.of() : executionResult.getCreatedBlockIds();
        String anchorBlockId = resolveAnchorBlockId(plan == null ? null : plan.getResolvedAnchor());
        log.info("DOC_ITER_RICH_VERIFY action={} expectedType={} createdBlockIds={} anchorBlockId={} beforeRevision={} afterRevision={} blockTypes={} blockOrderIndex={}",
                plan == null ? null : plan.getSemanticAction(),
                expectedTypeToken,
                createdBlockIds,
                anchorBlockId,
                before == null ? null : before.getRevisionId(),
                after == null ? null : after.getRevisionId(),
                summarizeBlockTypes(after),
                after == null ? null : after.getBlockOrderIndex());
        if (!createdBlockIds.isEmpty()) {
            boolean foundExpectedCreatedNode = false;
            for (String blockId : createdBlockIds) {
                DocumentStructureNode node = after.getBlockIndex().get(blockId);
                if (node == null) {
                    throw new IllegalStateException("目标状态校验失败：新增节点 " + blockId + " 未落盘");
                }
                if (containsTypeToken(node, expectedTypeToken)) {
                    foundExpectedCreatedNode = true;
                }
            }
            if (!foundExpectedCreatedNode && !hasNewExpectedTypeAfterAnchor(before, after, plan, expectedTypeToken)) {
                throw new IllegalStateException("目标状态校验失败：未发现新的 " + expectedTypeToken + " 节点");
            }
        } else if (!hasNewExpectedTypeAfterAnchor(before, after, plan, expectedTypeToken)) {
            if (canAcceptRevisionOnlyInsert(plan, before, after, expectedTypeToken)) {
                log.info("DOC_ITER_RICH_VERIFY fallback_accept_by_revision action={} expectedType={} beforeRevision={} afterRevision={}",
                        plan == null ? null : plan.getSemanticAction(),
                        expectedTypeToken,
                        before == null ? null : before.getRevisionId(),
                        after == null ? null : after.getRevisionId());
                return;
            }
            throw new IllegalStateException("目标状态校验失败：未发现新的 " + expectedTypeToken + " 节点");
        }

        if (anchorBlockId != null && after.getBlockOrderIndex() != null && after.getBlockOrderIndex().containsKey(anchorBlockId)) {
            Integer anchorOrder = after.getBlockOrderIndex().get(anchorBlockId);
            boolean foundAfterAnchor = createdBlockIds.stream()
                    .map(id -> after.getBlockOrderIndex() == null ? null : after.getBlockOrderIndex().get(id))
                    .anyMatch(index -> index != null && index > anchorOrder);
            if (!createdBlockIds.isEmpty() && !foundAfterAnchor) {
                throw new IllegalStateException("目标状态校验失败：新增节点未出现在目标锚点之后");
            }
        }
    }

    private void verifyExistingTargetPresent(DocumentEditPlan plan, DocumentStructureSnapshot after, String expectedTypeToken) {
        String blockId = resolveAnchorBlockId(plan == null ? null : plan.getResolvedAnchor());
        if (blockId == null) {
            throw new IllegalStateException("目标状态校验失败：缺少目标媒体锚点");
        }
        DocumentStructureNode node = after.getBlockIndex().get(blockId);
        if (node == null) {
            throw new IllegalStateException("目标状态校验失败：目标节点 " + blockId + " 不存在");
        }
        if (!containsTypeToken(node, expectedTypeToken)) {
            throw new IllegalStateException("目标状态校验失败：目标节点 " + blockId + " 不是预期的 " + expectedTypeToken + " 类型");
        }
    }

    private void verifyWhiteboardUpdated(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        String blockId = resolveAnchorBlockId(plan == null ? null : plan.getResolvedAnchor());
        if (blockId == null) {
            throw new IllegalStateException("目标状态校验失败：缺少 whiteboard 锚点");
        }
        DocumentStructureNode node = after.getBlockIndex().get(blockId);
        if (node == null || !containsTypeToken(node, "whiteboard")) {
            throw new IllegalStateException("目标状态校验失败：whiteboard 节点不存在");
        }
        long beforeRevision = before == null || before.getRevisionId() == null ? -1L : before.getRevisionId();
        long afterRevision = after.getRevisionId() == null ? -1L : after.getRevisionId();
        if (beforeRevision >= 0 && afterRevision >= 0 && afterRevision <= beforeRevision) {
            throw new IllegalStateException("目标状态校验失败：whiteboard 更新后 revision 未推进");
        }
    }

    private void verifyMediaNodeRemoved(DocumentStructureSnapshot before, DocumentStructureSnapshot after, DocumentEditPlan plan) {
        if (plan.getResolvedAnchor() == null || plan.getResolvedAnchor().getMediaAnchor() == null) return;
        String blockId = plan.getResolvedAnchor().getMediaAnchor().getBlockId();
        if (blockId == null) return;
        if (after != null && after.getBlockIndex() != null && after.getBlockIndex().containsKey(blockId)) {
            throw new IllegalStateException("目标状态校验失败：媒体节点 " + blockId + " 删除后仍存在");
        }
    }

    private void verifyLayoutReordered(DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        if (before == null || after == null) return;
        if (before.getTopLevelSequence() == null || after.getTopLevelSequence() == null) return;
        if (before.getTopLevelSequence().equals(after.getTopLevelSequence())) {
            throw new IllegalStateException("目标状态校验失败：布局重排后 top-level 顺序未变化");
        }
        if (!Set.copyOf(before.getTopLevelSequence()).equals(Set.copyOf(after.getTopLevelSequence()))) {
            throw new IllegalStateException("目标状态校验失败：布局重排后节点集合发生变化");
        }
    }

    private boolean hasBlockType(DocumentStructureSnapshot snapshot, String expectedTypeToken) {
        if (snapshot == null || snapshot.getBlockIndex() == null) {
            return false;
        }
        return snapshot.getBlockIndex().values().stream().anyMatch(node -> containsTypeToken(node, expectedTypeToken));
    }

    private boolean hasNewExpectedTypeAfterAnchor(
            DocumentStructureSnapshot before,
            DocumentStructureSnapshot after,
            DocumentEditPlan plan,
            String expectedTypeToken
    ) {
        if (!hasBlockType(after, expectedTypeToken)) {
            return false;
        }
        if (after == null || after.getBlockIndex() == null) {
            return false;
        }
        Set<String> beforeBlockIds = before == null || before.getBlockIndex() == null
                ? Set.of()
                : before.getBlockIndex().keySet();
        String anchorBlockId = plan == null ? null : resolveAnchorBlockId(plan.getResolvedAnchor());
        Integer anchorOrder = anchorBlockId == null || after.getBlockOrderIndex() == null
                ? null
                : after.getBlockOrderIndex().get(anchorBlockId);
        return after.getBlockIndex().values().stream()
                .filter(node -> containsTypeToken(node, expectedTypeToken))
                .filter(node -> !beforeBlockIds.contains(node.getBlockId()))
                .anyMatch(node -> anchorOrder == null
                        || after.getBlockOrderIndex() == null
                        || after.getBlockOrderIndex().get(node.getBlockId()) == null
                        || after.getBlockOrderIndex().get(node.getBlockId()) > anchorOrder);
    }

    private boolean containsTypeToken(DocumentStructureNode node, String expectedTypeToken) {
        if (node == null || node.getBlockType() == null || expectedTypeToken == null) {
            return false;
        }
        return node.getBlockType().toLowerCase(Locale.ROOT).contains(expectedTypeToken.toLowerCase(Locale.ROOT));
    }

    private boolean canAcceptRevisionOnlyInsert(
            DocumentEditPlan plan,
            DocumentStructureSnapshot before,
            DocumentStructureSnapshot after,
            String expectedTypeToken
    ) {
        if (plan == null || plan.getSemanticAction() != com.lark.imcollab.common.model.enums.DocumentSemanticActionType.INSERT_IMAGE_AFTER_ANCHOR) {
            return false;
        }
        if (!"image".equalsIgnoreCase(expectedTypeToken)) {
            return false;
        }
        long beforeRevision = before == null || before.getRevisionId() == null ? -1L : before.getRevisionId();
        long afterRevision = after == null || after.getRevisionId() == null ? -1L : after.getRevisionId();
        return beforeRevision >= 0 && afterRevision > beforeRevision;
    }

    private String resolveAnchorBlockId(ResolvedDocumentAnchor anchor) {
        if (anchor == null) {
            return null;
        }
        if (anchor.getMediaAnchor() != null) {
            return anchor.getMediaAnchor().getBlockId();
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

    private List<String> summarizeBlockTypes(DocumentStructureSnapshot snapshot) {
        if (snapshot == null || snapshot.getBlockIndex() == null || snapshot.getBlockIndex().isEmpty()) {
            return List.of();
        }
        return snapshot.getBlockIndex().values().stream()
                .map(node -> node == null ? "null" : node.getBlockId() + ":" + node.getBlockType())
                .sorted()
                .toList();
    }
}
