package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentSectionAnchor;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DocumentTargetStateVerifier {

    public void verify(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        if (plan == null || plan.getExpectedState() == null || plan.getExpectedState().getStateType() == null) {
            return;
        }
        DocumentExpectedStateType stateType = plan.getExpectedState().getStateType();
        switch (stateType) {
            case EXPECT_NO_CHANGE -> {}
            case EXPECT_CONTENT_APPENDED -> verifyContentAppended(before, after);
            case EXPECT_METADATA_BLOCK_PRESENT_AT_HEAD -> verifyMetadataAtHead(plan, after);
            case EXPECT_NEW_SECTION_BEFORE_TARGET_SECTION -> verifyNewSectionBeforeTarget(plan, before, after);
            case EXPECT_TEXT_REPLACED -> verifyTextReplaced(plan, before, after);
            case EXPECT_BLOCK_INSERTED_AFTER -> verifyBlockInserted(plan, after);
            case EXPECT_BLOCK_REMOVED -> verifyBlocksRemoved(plan, before, after);
            case EXPECT_SECTION_BODY_REMOVED -> verifySectionBodyRemoved(plan, before, after);
            case EXPECT_SECTION_REMOVED -> verifySectionRemoved(plan, before, after);
            default -> {}
        }
    }

    private void verifyContentAppended(DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        String afterMd = md(after);
        String beforeMd = md(before);
        if (afterMd.length() <= beforeMd.length()) {
            throw new IllegalStateException("目标状态校验失败：末尾追加后文档未增长");
        }
    }

    private void verifyMetadataAtHead(DocumentEditPlan plan, DocumentStructureSnapshot after) {
        if (after == null || after.getTopLevelSequence() == null || after.getTopLevelSequence().isEmpty()) {
            return;
        }
        String firstHeadingId = after.getTopLevelSequence().get(0);
        List<String> allBlockIds = allBlockIdsInOrder(after);
        int firstHeadingIndex = allBlockIds.indexOf(firstHeadingId);
        String generated = trim(plan.getGeneratedContent());
        if (generated.isBlank()) {
            return;
        }
        // 校验：首个 top-level heading 之前存在新增内容的关键片段
        String beforeHeadingContent = firstHeadingIndex > 0
                ? blockRangeText(after, allBlockIds.subList(0, firstHeadingIndex))
                : "";
        if (!containsMeaningfulFragment(beforeHeadingContent, generated)) {
            throw new IllegalStateException("目标状态校验失败：文首 metadata 未出现在首章节之前");
        }
        // 校验：原首章节 heading 未被复制/吞掉
        if (after.getHeadingIndex() == null || !after.getHeadingIndex().containsKey(firstHeadingId)) {
            throw new IllegalStateException("目标状态校验失败：原首章节 heading block 丢失");
        }
    }

    private void verifyNewSectionBeforeTarget(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        DocumentSectionAnchor targetSection = plan.getResolvedAnchor() == null ? null : plan.getResolvedAnchor().getSectionAnchor();
        if (targetSection == null || targetSection.getHeadingBlockId() == null) {
            return;
        }
        String targetHeadingId = targetSection.getHeadingBlockId();
        List<String> afterTopLevel = after.getTopLevelSequence();
        if (afterTopLevel == null) {
            return;
        }
        int targetIndex = afterTopLevel.indexOf(targetHeadingId);
        if (targetIndex < 0) {
            throw new IllegalStateException("目标状态校验失败：目标章节 heading 在 after 快照中消失");
        }
        // 校验：目标章节之前的 top-level 数量比 before 多
        List<String> beforeTopLevel = before.getTopLevelSequence();
        int beforeTargetIndex = beforeTopLevel == null ? -1 : beforeTopLevel.indexOf(targetHeadingId);
        if (beforeTargetIndex >= 0 && targetIndex <= beforeTargetIndex) {
            throw new IllegalStateException("目标状态校验失败：新章节未插入到目标章节之前");
        }
    }

    private void verifyTextReplaced(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        String generated = trim(plan.getGeneratedContent());
        if (generated.isBlank()) {
            return;
        }
        if (!normalize(md(after)).contains(normalize(generated))) {
            throw new IllegalStateException("目标状态校验失败：替换后未找到新内容");
        }
        // 校验旧文本已消失（仅当 oldText 唯一命中时）
        String oldText = plan.getPatchOperations() == null ? null
                : plan.getPatchOperations().stream()
                .filter(op -> op.getOldText() != null && !op.getOldText().isBlank())
                .map(op -> op.getOldText())
                .findFirst().orElse(null);
        if (oldText != null && normalize(md(after)).contains(normalize(oldText))) {
            throw new IllegalStateException("目标状态校验失败：str_replace 后原文仍存在");
        }
    }

    private void verifyBlockInserted(DocumentEditPlan plan, DocumentStructureSnapshot after) {
        String generated = trim(plan.getGeneratedContent());
        if (generated.isBlank()) {
            return;
        }
        if (!containsMeaningfulFragment(md(after), generated)) {
            throw new IllegalStateException("目标状态校验失败：插入内容未落盘");
        }
    }

    private void verifyBlocksRemoved(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        Set<String> removedIds = targetBlockIds(plan);
        if (removedIds.isEmpty()) {
            return;
        }
        Set<String> afterIds = blockIdSet(after);
        for (String id : removedIds) {
            if (afterIds.contains(id)) {
                throw new IllegalStateException("目标状态校验失败：block " + id + " 删除后仍存在");
            }
        }
    }

    private void verifySectionBodyRemoved(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        DocumentSectionAnchor section = plan.getResolvedAnchor() == null ? null : plan.getResolvedAnchor().getSectionAnchor();
        if (section == null) {
            return;
        }
        // heading 必须仍在
        if (section.getHeadingBlockId() != null && !blockIdSet(after).contains(section.getHeadingBlockId())) {
            throw new IllegalStateException("目标状态校验失败：章节 heading 在删除正文后消失");
        }
        // body blocks 必须全部消失
        List<String> bodyIds = section.getBodyBlockIds();
        if (bodyIds == null || bodyIds.isEmpty()) {
            return;
        }
        Set<String> afterIds = blockIdSet(after);
        for (String id : bodyIds) {
            if (afterIds.contains(id)) {
                throw new IllegalStateException("目标状态校验失败：章节正文 block " + id + " 删除后仍存在");
            }
        }
    }

    private void verifySectionRemoved(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        DocumentSectionAnchor section = plan.getResolvedAnchor() == null ? null : plan.getResolvedAnchor().getSectionAnchor();
        if (section == null || section.getAllBlockIds() == null) {
            return;
        }
        Set<String> afterIds = blockIdSet(after);
        for (String id : section.getAllBlockIds()) {
            if (afterIds.contains(id)) {
                throw new IllegalStateException("目标状态校验失败：章节 block " + id + " 删除后仍存在");
            }
        }
    }

    private Set<String> targetBlockIds(DocumentEditPlan plan) {
        if (plan.getPatchOperations() == null) {
            return Set.of();
        }
        return plan.getPatchOperations().stream()
                .filter(op -> op.getBlockId() != null && !op.getBlockId().isBlank())
                .flatMap(op -> java.util.Arrays.stream(op.getBlockId().split(",")))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .collect(Collectors.toSet());
    }

    private Set<String> blockIdSet(DocumentStructureSnapshot snapshot) {
        if (snapshot == null || snapshot.getBlockIndex() == null) {
            return Set.of();
        }
        return snapshot.getBlockIndex().keySet();
    }

    private List<String> allBlockIdsInOrder(DocumentStructureSnapshot snapshot) {
        if (snapshot == null || snapshot.getBlockIndex() == null) {
            return List.of();
        }
        return List.copyOf(snapshot.getBlockIndex().keySet());
    }

    private String blockRangeText(DocumentStructureSnapshot snapshot, List<String> blockIds) {
        if (snapshot == null || snapshot.getBlockIndex() == null || blockIds == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String id : blockIds) {
            var node = snapshot.getBlockIndex().get(id);
            if (node != null && node.getPlainText() != null) {
                sb.append(node.getPlainText()).append("\n");
            }
        }
        return sb.toString();
    }

    private boolean containsMeaningfulFragment(String haystack, String candidate) {
        if (haystack == null || candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalizedHaystack = normalize(haystack);
        for (String line : candidate.split("\\R+")) {
            String fragment = normalize(line.replaceAll("^[#>*\\-\\d.\\s]+", ""));
            if (fragment.length() >= 4 && normalizedHaystack.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private String md(DocumentStructureSnapshot snapshot) {
        return snapshot == null || snapshot.getRawFullMarkdown() == null ? "" : snapshot.getRawFullMarkdown();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
