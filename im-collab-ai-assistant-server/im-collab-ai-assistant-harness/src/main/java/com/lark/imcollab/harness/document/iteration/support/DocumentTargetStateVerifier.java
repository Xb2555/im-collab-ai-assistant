package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.*;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DocumentTargetStateVerifier {

    public void verify(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        if (plan == null || plan.getExpectedState() == null || plan.getExpectedState().getStateType() == null) return;
        switch (plan.getExpectedState().getStateType()) {
            case EXPECT_NO_CHANGE -> {}
            case EXPECT_CONTENT_APPENDED -> verifyContentAppended(before, after);
            case EXPECT_METADATA_BLOCK_PRESENT_AT_HEAD -> verifyMetadataAtHead(plan, before, after);
            case EXPECT_NEW_SECTION_BEFORE_TARGET_SECTION -> verifyNewSectionBefore(plan, before, after);
            case EXPECT_TEXT_REPLACED -> verifyTextReplaced(plan, before, after);
            case EXPECT_BLOCK_INSERTED_AFTER -> verifyBlockInsertedAfter(plan, before, after);
            case EXPECT_BLOCK_REMOVED -> verifyBlocksRemoved(plan, after);
            case EXPECT_SECTION_BODY_REMOVED -> verifySectionBodyRemoved(plan, after);
            case EXPECT_SECTION_REMOVED -> verifySectionRemoved(plan, after);
            default -> {}
        }
    }

    private void verifyContentAppended(DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        int beforeCount = blockCount(before);
        int afterCount = blockCount(after);
        if (afterCount <= beforeCount) {
            throw new IllegalStateException("目标状态校验失败：文档 block 数量未增长（before=" + beforeCount + " after=" + afterCount + "）");
        }
    }

    private void verifyMetadataAtHead(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        if (after == null || after.getTopLevelSequence() == null || after.getTopLevelSequence().isEmpty()) return;
        String firstHeadingId = after.getTopLevelSequence().get(0);
        // 首章节 heading 必须仍在
        if (after.getHeadingIndex() == null || !after.getHeadingIndex().containsKey(firstHeadingId)) {
            throw new IllegalStateException("目标状态校验失败：原首章节 heading block 丢失");
        }
        // 首章节之前必须有新增 block（结构对比）
        List<String> beforeHead = blocksBeforeFirstHeading(before);
        List<String> afterHead = blocksBeforeFirstHeading(after);
        if (afterHead.size() <= beforeHead.size()) {
            throw new IllegalStateException("目标状态校验失败：文首 metadata block 未出现在首章节之前（before=" + beforeHead.size() + " after=" + afterHead.size() + "）");
        }
    }

    private void verifyNewSectionBefore(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        DocumentSectionAnchor target = plan.getResolvedAnchor() == null ? null : plan.getResolvedAnchor().getSectionAnchor();
        if (target == null || target.getHeadingBlockId() == null) return;
        String targetId = target.getHeadingBlockId();
        List<String> afterTopLevel = after == null ? null : after.getTopLevelSequence();
        if (afterTopLevel == null) return;
        int afterIdx = afterTopLevel.indexOf(targetId);
        if (afterIdx < 0) throw new IllegalStateException("目标状态校验失败：目标章节 heading 在 after 快照中消失");
        List<String> beforeTopLevel = before == null ? null : before.getTopLevelSequence();
        int beforeIdx = beforeTopLevel == null ? -1 : beforeTopLevel.indexOf(targetId);
        if (beforeIdx >= 0 && afterIdx <= beforeIdx) {
            throw new IllegalStateException("目标状态校验失败：新章节未插入到目标章节之前（before_idx=" + beforeIdx + " after_idx=" + afterIdx + "）");
        }
    }

    private void verifyTextReplaced(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        // 主判据：anchor 绑定的 sourceBlockIds 内容已变化
        DocumentTextAnchor textAnchor = plan.getResolvedAnchor() == null ? null : plan.getResolvedAnchor().getTextAnchor();
        String oldText = firstOldText(plan);
        if (textAnchor != null && textAnchor.getSourceBlockIds() != null && !textAnchor.getSourceBlockIds().isEmpty()) {
            for (String blockId : textAnchor.getSourceBlockIds()) {
                DocumentStructureNode afterNode = after == null || after.getBlockIndex() == null ? null : after.getBlockIndex().get(blockId);
                if (afterNode != null && oldText != null && afterNode.getPlainText() != null
                        && afterNode.getPlainText().contains(oldText)) {
                    throw new IllegalStateException("目标状态校验失败：block " + blockId + " 中原文仍存在");
                }
            }
        } else if (oldText != null) {
            // 辅助：全文不再包含旧文本
            if (fullText(after).contains(oldText)) {
                throw new IllegalStateException("目标状态校验失败：str_replace 后原文仍存在（全文检查）");
            }
        }
    }

    private void verifyBlockInsertedAfter(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        // 主判据：after block 数量 > before
        if (blockCount(after) <= blockCount(before)) {
            throw new IllegalStateException("目标状态校验失败：插入后 block 数量未增长");
        }
        // 辅助：anchor 后方有新 block
        String anchorBlockId = resolveAnchorBlockId(plan);
        if (anchorBlockId != null && after != null && after.getOrderedBlockIds() != null) {
            int anchorIdx = after.getOrderedBlockIds().indexOf(anchorBlockId);
            if (anchorIdx >= 0 && anchorIdx + 1 >= after.getOrderedBlockIds().size()) {
                throw new IllegalStateException("目标状态校验失败：锚点 block 后方无新增 block");
            }
        }
    }

    private void verifyBlocksRemoved(DocumentEditPlan plan, DocumentStructureSnapshot after) {
        Set<String> removedIds = targetBlockIds(plan);
        if (removedIds.isEmpty()) return;
        Set<String> afterIds = blockIdSet(after);
        for (String id : removedIds) {
            if (afterIds.contains(id)) throw new IllegalStateException("目标状态校验失败：block " + id + " 删除后仍存在");
        }
    }

    private void verifySectionBodyRemoved(DocumentEditPlan plan, DocumentStructureSnapshot after) {
        DocumentSectionAnchor section = plan.getResolvedAnchor() == null ? null : plan.getResolvedAnchor().getSectionAnchor();
        if (section == null) return;
        if (section.getHeadingBlockId() != null && !blockIdSet(after).contains(section.getHeadingBlockId())) {
            throw new IllegalStateException("目标状态校验失败：章节 heading 在删除正文后消失");
        }
        List<String> bodyIds = section.getBodyBlockIds();
        if (bodyIds == null || bodyIds.isEmpty()) return;
        Set<String> afterIds = blockIdSet(after);
        for (String id : bodyIds) {
            if (afterIds.contains(id)) throw new IllegalStateException("目标状态校验失败：章节正文 block " + id + " 删除后仍存在");
        }
    }

    private void verifySectionRemoved(DocumentEditPlan plan, DocumentStructureSnapshot after) {
        DocumentSectionAnchor section = plan.getResolvedAnchor() == null ? null : plan.getResolvedAnchor().getSectionAnchor();
        if (section == null || section.getAllBlockIds() == null) return;
        Set<String> afterIds = blockIdSet(after);
        for (String id : section.getAllBlockIds()) {
            if (afterIds.contains(id)) throw new IllegalStateException("目标状态校验失败：章节 block " + id + " 删除后仍存在");
        }
    }

    // ---- helpers ----

    private List<String> blocksBeforeFirstHeading(DocumentStructureSnapshot snapshot) {
        if (snapshot == null || snapshot.getOrderedBlockIds() == null || snapshot.getTopLevelSequence() == null
                || snapshot.getTopLevelSequence().isEmpty()) return List.of();
        String firstHeadingId = snapshot.getTopLevelSequence().get(0);
        List<String> result = new java.util.ArrayList<>();
        for (String id : snapshot.getOrderedBlockIds()) {
            if (id.equals(firstHeadingId)) break;
            result.add(id);
        }
        return result;
    }

    private int blockCount(DocumentStructureSnapshot snapshot) {
        if (snapshot == null || snapshot.getBlockIndex() == null) return 0;
        return snapshot.getBlockIndex().size();
    }

    private Set<String> blockIdSet(DocumentStructureSnapshot snapshot) {
        if (snapshot == null || snapshot.getBlockIndex() == null) return Set.of();
        return snapshot.getBlockIndex().keySet();
    }

    private Set<String> targetBlockIds(DocumentEditPlan plan) {
        if (plan.getPatchOperations() == null) return Set.of();
        return plan.getPatchOperations().stream()
                .filter(op -> op.getBlockId() != null && !op.getBlockId().isBlank())
                .flatMap(op -> java.util.Arrays.stream(op.getBlockId().split(",")))
                .map(String::trim).filter(id -> !id.isBlank())
                .collect(Collectors.toSet());
    }

    private String firstOldText(DocumentEditPlan plan) {
        if (plan.getPatchOperations() == null) return null;
        return plan.getPatchOperations().stream()
                .filter(op -> op.getOldText() != null && !op.getOldText().isBlank())
                .map(DocumentPatchOperation::getOldText).findFirst().orElse(null);
    }

    private String resolveAnchorBlockId(DocumentEditPlan plan) {
        ResolvedDocumentAnchor anchor = plan.getResolvedAnchor();
        if (anchor == null) return null;
        if (anchor.getBlockAnchor() != null) return anchor.getBlockAnchor().getBlockId();
        if (anchor.getSectionAnchor() != null) {
            List<String> all = anchor.getSectionAnchor().getAllBlockIds();
            return all != null && !all.isEmpty() ? all.get(all.size() - 1) : null;
        }
        return null;
    }

    private String fullText(DocumentStructureSnapshot snapshot) {
        if (snapshot == null || snapshot.getBlockIndex() == null) return "";
        StringBuilder sb = new StringBuilder();
        snapshot.getBlockIndex().values().forEach(n -> { if (n.getPlainText() != null) sb.append(n.getPlainText()); });
        return sb.toString();
    }
}
