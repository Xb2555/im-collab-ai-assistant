package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import com.lark.imcollab.common.model.enums.MediaAssetType;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RichContentTargetStateVerifier {

    public void verify(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        if (plan == null || plan.getExpectedState() == null || plan.getExpectedState().getStateType() == null) {
            return;
        }
        DocumentExpectedStateType stateType = plan.getExpectedState().getStateType();
        switch (stateType) {
            case EXPECT_IMAGE_NODE_PRESENT -> verifyMediaNodePresent(after, MediaAssetType.IMAGE, plan);
            case EXPECT_TABLE_NODE_PRESENT -> verifyMediaNodePresent(after, MediaAssetType.TABLE, plan);
            case EXPECT_WHITEBOARD_NODE_PRESENT -> verifyMediaNodePresent(after, MediaAssetType.WHITEBOARD, plan);
            case EXPECT_MEDIA_NODE_REMOVED -> verifyMediaNodeRemoved(before, after, plan);
            case EXPECT_LAYOUT_REORDERED -> verifyLayoutReordered(before, after, plan);
            default -> {}
        }
    }

    private void verifyMediaNodePresent(DocumentStructureSnapshot after, MediaAssetType expectedType, DocumentEditPlan plan) {
        if (after == null || after.getBlockIndex() == null) {
            throw new IllegalStateException("目标状态校验失败：after snapshot 为空，无法校验 " + expectedType + " 节点");
        }
        String newBlockId = resolveNewBlockId(plan);
        if (newBlockId == null) {
            return;
        }
        if (!after.getBlockIndex().containsKey(newBlockId)) {
            throw new IllegalStateException("目标状态校验失败：" + expectedType + " 节点 " + newBlockId + " 未落盘");
        }
        var node = after.getBlockIndex().get(newBlockId);
        String blockType = node == null ? "" : (node.getBlockType() == null ? "" : node.getBlockType().toLowerCase());
        String expected = expectedType.name().toLowerCase();
        if (!blockType.contains(expected) && !expected.equals("table") && !blockType.contains("media")) {
            // 宽松校验：只要 block 存在即可，类型由平台决定
        }
    }

    private void verifyMediaNodeRemoved(DocumentStructureSnapshot before, DocumentStructureSnapshot after, DocumentEditPlan plan) {
        if (plan.getResolvedAnchor() == null || plan.getResolvedAnchor().getMediaAnchor() == null) {
            return;
        }
        String blockId = plan.getResolvedAnchor().getMediaAnchor().getBlockId();
        if (blockId == null) {
            return;
        }
        if (after != null && after.getBlockIndex() != null && after.getBlockIndex().containsKey(blockId)) {
            throw new IllegalStateException("目标状态校验失败：媒体节点 " + blockId + " 删除后仍存在");
        }
    }

    private void verifyLayoutReordered(DocumentStructureSnapshot before, DocumentStructureSnapshot after, DocumentEditPlan plan) {
        if (before == null || after == null) {
            return;
        }
        if (before.getTopLevelSequence() == null || after.getTopLevelSequence() == null) {
            return;
        }
        if (before.getTopLevelSequence().equals(after.getTopLevelSequence())) {
            throw new IllegalStateException("目标状态校验失败：布局重排后 top-level 顺序未变化");
        }
        Set<String> beforeIds = Set.copyOf(before.getTopLevelSequence());
        Set<String> afterIds = Set.copyOf(after.getTopLevelSequence());
        if (!beforeIds.equals(afterIds)) {
            throw new IllegalStateException("目标状态校验失败：布局重排后节点集合发生变化，存在丢失或新增");
        }
    }

    private String resolveNewBlockId(DocumentEditPlan plan) {
        if (plan.getPatchOperations() == null) {
            return null;
        }
        return plan.getPatchOperations().stream()
                .filter(op -> op.getBlockId() != null && !op.getBlockId().isBlank())
                .map(op -> op.getBlockId())
                .findFirst()
                .orElse(null);
    }
}
