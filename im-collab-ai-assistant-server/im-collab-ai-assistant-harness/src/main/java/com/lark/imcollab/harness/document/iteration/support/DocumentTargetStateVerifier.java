package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import org.springframework.stereotype.Component;

@Component
public class DocumentTargetStateVerifier {

    public void verify(DocumentEditPlan plan, DocumentStructureSnapshot before, DocumentStructureSnapshot after) {
        if (plan == null || plan.getExpectedState() == null || plan.getExpectedState().getStateType() == null) {
            return;
        }
        DocumentExpectedStateType stateType = plan.getExpectedState().getStateType();
        String afterMarkdown = after == null ? "" : nullToEmpty(after.getRawFullMarkdown());
        String beforeMarkdown = before == null ? "" : nullToEmpty(before.getRawFullMarkdown());
        switch (stateType) {
            case EXPECT_NO_CHANGE -> {
                return;
            }
            case EXPECT_CONTENT_APPENDED -> {
                if (afterMarkdown.length() <= beforeMarkdown.length()) {
                    throw new IllegalStateException("目标状态校验失败：末尾追加后文档未增长");
                }
            }
            case EXPECT_METADATA_BLOCK_PRESENT_AT_HEAD -> {
                String generated = nullToEmpty(plan.getGeneratedContent()).trim();
                if (!generated.isBlank() && !afterMarkdown.contains(firstMeaningfulLine(generated))) {
                    throw new IllegalStateException("目标状态校验失败：文首 metadata 未落盘");
                }
            }
            case EXPECT_TEXT_REPLACED, EXPECT_BLOCK_INSERTED_AFTER, EXPECT_NEW_SECTION_BEFORE_TARGET_SECTION -> {
                String generated = nullToEmpty(plan.getGeneratedContent()).trim();
                if (!generated.isBlank() && !afterMarkdown.contains(firstMeaningfulLine(generated))) {
                    throw new IllegalStateException("目标状态校验失败：未找到预期新内容");
                }
            }
            case EXPECT_BLOCK_REMOVED -> {
                String preview = plan.getSelector() == null ? "" : nullToEmpty(plan.getSelector().getMatchedExcerpt()).trim();
                if (!preview.isBlank() && normalize(afterMarkdown).contains(normalize(preview))) {
                    throw new IllegalStateException("目标状态校验失败：目标 block 删除后文本仍存在");
                }
            }
            case EXPECT_SECTION_BODY_REMOVED, EXPECT_SECTION_REMOVED -> {
                String preview = plan.getSelector() == null ? "" : nullToEmpty(plan.getSelector().getMatchedExcerpt()).trim();
                if (!preview.isBlank() && afterMarkdown.contains(preview) && !preview.equals(plan.getSelector().getLocatorValue())) {
                    throw new IllegalStateException("目标状态校验失败：目标内容删除后仍存在");
                }
            }
            default -> {
            }
        }
    }

    private String firstMeaningfulLine(String text) {
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                return trimmed;
            }
        }
        return text;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }
}
