package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.entity.RichContentExecutionResult;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class RichContentTargetStateVerifier {

    public void verify(DocumentEditPlan plan, RichContentExecutionResult executionResult,
                       DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        if (plan == null || plan.getExpectedState() == null || plan.getExpectedState().getStateType() == null) {
            return;
        }
        DocumentExpectedStateType stateType = plan.getExpectedState().getStateType();
        switch (stateType) {
            case EXPECT_IMAGE_NODE_PRESENT, EXPECT_TABLE_NODE_PRESENT, EXPECT_WHITEBOARD_NODE_PRESENT ->
                    verifyMediaNodePresent(executionResult, after);
            case EXPECT_MEDIA_NODE_REMOVED -> verifyMediaNodeRemoved(before, after, plan);
            case EXPECT_LAYOUT_REORDERED -> verifyLayoutReordered(before, after);
            default -> {}
        }
    }

    private void verifyMediaNodePresent(RichContentExecutionResult executionResult, DocumentStructureSnapshot after) {
        if (after == null || after.getBlockIndex() == null) {
            throw new IllegalStateException("目标状态校验失败：after snapshot 为空");
        }
        List<String> createdBlockIds = executionResult == null ? List.of() : executionResult.getCreatedBlockIds();
        if (createdBlockIds.isEmpty()) {
            throw new IllegalStateException("目标状态校验失败：执行结果中无新增 block id");
        }
        for (String blockId : createdBlockIds) {
            if (!after.getBlockIndex().containsKey(blockId)) {
                throw new IllegalStateException("目标状态校验失败：新增节点 " + blockId + " 未落盘");
            }
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
}
