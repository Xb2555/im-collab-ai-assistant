package com.lark.imcollab.harness.document.template;

import com.lark.imcollab.common.model.entity.ComposedDocumentDraft;
import com.lark.imcollab.common.model.entity.DocumentPlan;
import com.lark.imcollab.common.model.entity.DocumentPlanSection;
import com.lark.imcollab.common.model.entity.DocumentReviewResult;
import com.lark.imcollab.common.model.entity.DocumentSectionDraft;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTemplateRendererTests {

    private final DocumentBodyNormalizer bodyNormalizer = new DocumentBodyNormalizer();
    private final DocumentTemplateRenderer renderer = new DocumentTemplateRenderer(bodyNormalizer);

    @Test
    void shouldRenderSectionsInFrozenPlanOrder() {
        DocumentPlan plan = plan(
                section("section-1", 1, "一、活动背景"),
                section("section-2", 2, "二、活动时间与地点"),
                section("section-3", 3, "三、活动预算")
        );
        ComposedDocumentDraft draft = composedDraft(
                draft("section-1", "活动背景", "1.1 背景说明"),
                draft("section-2", "活动时间与地点", "2.1 时间安排"),
                draft("section-3", "活动预算", "3.1 预算拆分")
        );

        String markdown = renderer.render(
                DocumentTemplateType.REPORT,
                plan,
                draft,
                DocumentReviewResult.builder().summary("通过").build(),
                ""
        );

        assertThat(markdown.indexOf("## 一、活动背景")).isLessThan(markdown.indexOf("## 二、活动时间与地点"));
        assertThat(markdown.indexOf("## 二、活动时间与地点")).isLessThan(markdown.indexOf("## 三、活动预算"));
        assertThat(markdown).contains("### 1.1 背景说明");
        assertThat(markdown).contains("### 2.1 时间安排");
        assertThat(markdown).contains("### 3.1 预算拆分");
    }

    @Test
    void shouldRespectComposedMarkdownWithoutTemplateReordering() {
        DocumentPlan plan = plan(
                section("section-1", 1, "一、目标"),
                section("section-2", 2, "二、方案")
        );
        ComposedDocumentDraft draft = ComposedDocumentDraft.builder()
                .taskId("task-1")
                .planId("task-1:plan")
                .orderedSections(List.of(
                        draft("section-1", "目标", "1.1 目标拆解"),
                        draft("section-2", "方案", "2.1 方案执行")
                ))
                .composedMarkdown("## 一、目标\n\n### 1.1 目标拆解\n\n## 二、方案\n\n### 2.1 方案执行")
                .build();

        String markdown = renderer.render(
                DocumentTemplateType.TECHNICAL_PLAN,
                plan,
                draft,
                DocumentReviewResult.builder().summary("通过").build(),
                ""
        );

        assertThat(markdown).contains("## 一、目标");
        assertThat(markdown).contains("## 二、方案");
        assertThat(markdown).doesNotContain("## 一、项目背景与上下文");
        assertThat(markdown).doesNotContain("## 七、补充设计细节");
    }

    @Test
    void shouldStripLeadingOrdinalsFromRenderedHeadings() {
        DocumentPlan plan = plan(section("section-1", 1, "九、补充说明"));
        ComposedDocumentDraft draft = composedDraft(draft("section-1", "九、补充说明", "9.1 说明细节"));

        String markdown = renderer.render(
                DocumentTemplateType.TECHNICAL_ARCHITECTURE,
                plan,
                draft,
                DocumentReviewResult.builder().summary("通过").build(),
                ""
        );

        assertThat(markdown).contains("## 一、补充说明");
        assertThat(markdown).doesNotContain("## 九、补充说明");
        assertThat(markdown).contains("### 1.1 说明细节");
    }

    private DocumentPlan plan(DocumentPlanSection... sections) {
        return DocumentPlan.builder()
                .planId("task-1:plan")
                .taskId("task-1")
                .title("文档")
                .templateType("REPORT")
                .orderedSections(List.of(sections))
                .build();
    }

    private DocumentPlanSection section(String sectionId, int index, String heading) {
        return DocumentPlanSection.builder()
                .sectionId(sectionId)
                .index(index)
                .heading(heading)
                .build();
    }

    private ComposedDocumentDraft composedDraft(DocumentSectionDraft... drafts) {
        return ComposedDocumentDraft.builder()
                .taskId("task-1")
                .planId("task-1:plan")
                .orderedSections(List.of(drafts))
                .build();
    }

    private DocumentSectionDraft draft(String sectionId, String heading, String body) {
        return DocumentSectionDraft.builder()
                .sectionId(sectionId)
                .heading(heading)
                .body(body)
                .build();
    }
}
