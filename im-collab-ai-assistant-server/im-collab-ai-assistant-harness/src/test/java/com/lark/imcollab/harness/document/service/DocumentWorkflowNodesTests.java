package com.lark.imcollab.harness.document.service;

import com.lark.imcollab.common.model.entity.ComposedDocumentDraft;
import com.lark.imcollab.common.model.entity.DocumentPlan;
import com.lark.imcollab.common.model.entity.DocumentPlanSection;
import com.lark.imcollab.common.model.entity.DocumentReviewResult;
import com.lark.imcollab.common.model.entity.DocumentSectionDraft;
import com.lark.imcollab.harness.document.template.DocumentBodyNormalizer;
import com.lark.imcollab.harness.document.template.DocumentTemplateRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentWorkflowNodesTests {

    private final DocumentBodyNormalizer bodyNormalizer = new DocumentBodyNormalizer();
    private final DocumentTemplateRenderer renderer = new DocumentTemplateRenderer(bodyNormalizer);
    private final DocumentWorkflowNodes nodes = new DocumentWorkflowNodes(
            null,
            bodyNormalizer,
            renderer,
            null,
            null,
            null,
            null,
            null,
            null,
            new ObjectMapper()
    );

    @Test
    void shouldBlockPublishWhenDiagramSectionHasOnlyHeading() {
        DocumentPlan plan = DocumentPlan.builder()
                .planId("task-1:plan")
                .taskId("task-1")
                .title("文档")
                .orderedSections(List.of(
                        DocumentPlanSection.builder().sectionId("section-1").index(1).heading("一、背景").build(),
                        DocumentPlanSection.builder().sectionId("section-2").index(2).heading("二、数据流转图").build()
                ))
                .build();

        ComposedDocumentDraft composedDraft = nodes.composeDocumentDraft(
                "task-1",
                plan,
                List.of(
                        DocumentSectionDraft.builder().sectionId("section-1").heading("背景").body("1.1 说明").build(),
                        DocumentSectionDraft.builder().sectionId("section-2").heading("数据流转图").body("## 数据流转图").build()
                ),
                "",
                "DATA_FLOW",
                DocumentReviewResult.builder().summary("通过").build()
        );

        assertThatThrownBy(() -> nodes.ensurePublishable(plan, composedDraft))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete sections");
    }

    @Test
    void shouldBlockPublishWhenSupplementalSectionCannotBeMapped() {
        DocumentPlan plan = DocumentPlan.builder()
                .planId("task-1:plan")
                .taskId("task-1")
                .title("文档")
                .orderedSections(List.of(
                        DocumentPlanSection.builder().sectionId("section-1").index(1).heading("一、背景").build(),
                        DocumentPlanSection.builder().sectionId("section-2").index(2).heading("二、时间计划").build()
                ))
                .build();

        ComposedDocumentDraft composedDraft = nodes.composeDocumentDraft(
                "task-1",
                plan,
                List.of(
                        DocumentSectionDraft.builder().sectionId("section-1").heading("背景").body("1.1 说明").build(),
                        DocumentSectionDraft.builder().sectionId("section-2").heading("时间计划").body("2.1 安排").build()
                ),
                "",
                "",
                DocumentReviewResult.builder()
                        .summary("已补齐")
                        .supplementalSections(List.of(
                                DocumentSectionDraft.builder().heading("里程碑表格").body("表格内容").build()
                        ))
                        .build()
        );
        composedDraft.setComposedMarkdown(renderer.render(
                com.lark.imcollab.harness.document.template.DocumentTemplateType.REPORT,
                plan,
                composedDraft,
                DocumentReviewResult.builder().summary("通过").build(),
                ""
        ));

        assertThatThrownBy(() -> nodes.ensurePublishable(plan, composedDraft))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unmatched supplemental");
    }
}
