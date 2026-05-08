package com.lark.imcollab.harness.document.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.model.entity.ComposedDocumentDraft;
import com.lark.imcollab.common.model.entity.DocumentPlan;
import com.lark.imcollab.common.model.entity.DocumentPlanSection;
import com.lark.imcollab.common.model.entity.DocumentReviewResult;
import com.lark.imcollab.common.model.entity.DocumentSectionDraft;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.harness.document.support.DocumentExecutionSupport;
import com.lark.imcollab.harness.document.template.DocumentBodyNormalizer;
import com.lark.imcollab.harness.document.template.DocumentTemplateRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.harness.document.workflow.DocumentStateKeys;
import com.lark.imcollab.harness.support.ExecutionInterruptedException;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void summaryOnlyRequestDoesNotCreateLarkDoc() {
        DocumentExecutionSupport support = mock(DocumentExecutionSupport.class);
        LarkDocTool larkDocTool = mock(LarkDocTool.class);
        when(support.findSummaryStepId("task-1")).thenReturn(Optional.of("summary-step"));
        DocumentWorkflowNodes summaryNodes = new DocumentWorkflowNodes(
                support,
                bodyNormalizer,
                renderer,
                null,
                null,
                null,
                null,
                null,
                larkDocTool,
                new ObjectMapper()
        );
        DocumentPlan plan = DocumentPlan.builder()
                .planId("task-1:plan")
                .taskId("task-1")
                .title("项目进展摘要")
                .orderedSections(List.of(DocumentPlanSection.builder()
                        .sectionId("section-1")
                        .index(1)
                        .heading("进展")
                        .build()))
                .build();
        ComposedDocumentDraft draft = ComposedDocumentDraft.builder()
                .orderedSections(List.of(DocumentSectionDraft.builder()
                        .sectionId("section-1")
                        .heading("进展")
                        .body("本周完成 Planner 和 IM 链路验证。")
                        .build()))
                .composedMarkdown("## 进展\n本周完成 Planner 和 IM 链路验证。")
                .build();

        summaryNodes.writeDocAndSync(new OverAllState(Map.of(
                DocumentStateKeys.TASK_ID, "task-1",
                DocumentStateKeys.ALLOWED_ARTIFACTS, List.of("SUMMARY"),
                DocumentStateKeys.DOCUMENT_PLAN, plan,
                DocumentStateKeys.COMPOSED_DRAFT, draft,
                DocumentStateKeys.REVIEW_RESULT, DocumentReviewResult.builder().summary("摘要已生成").build()
        )), RunnableConfig.builder().threadId("task-1").build()).join();

        verify(larkDocTool, never()).createDoc(anyString(), anyString());
        verify(support).saveArtifact(eq("task-1"), eq("summary-step"), eq(ArtifactType.SUMMARY),
                eq("项目进展摘要 - 摘要"), org.mockito.ArgumentMatchers.contains("本周完成 Planner"), eq(null));
        verify(support).markSummaryStepCompleted(eq("task-1"), org.mockito.ArgumentMatchers.contains("本周完成 Planner"));
        verify(support).publishEvent("task-1", null, TaskEventType.ARTIFACT_CREATED);
        verify(support).publishEvent("task-1", null, TaskEventType.STEP_COMPLETED);
    }

    @Test
    void interruptedExecutionDoesNotCreateLarkDoc() {
        DocumentExecutionSupport support = mock(DocumentExecutionSupport.class);
        LarkDocTool larkDocTool = mock(LarkDocTool.class);
        doThrow(new ExecutionInterruptedException("stale attempt"))
                .when(support).ensureExecutionCanContinue("task-1");
        DocumentWorkflowNodes interruptedNodes = new DocumentWorkflowNodes(
                support,
                bodyNormalizer,
                renderer,
                null,
                null,
                null,
                null,
                null,
                larkDocTool,
                new ObjectMapper()
        );
        DocumentPlan plan = DocumentPlan.builder()
                .planId("task-1:plan")
                .taskId("task-1")
                .title("旧计划测试文档")
                .orderedSections(List.of(DocumentPlanSection.builder()
                        .sectionId("section-1")
                        .index(1)
                        .heading("背景")
                        .build()))
                .build();
        ComposedDocumentDraft draft = ComposedDocumentDraft.builder()
                .orderedSections(List.of(DocumentSectionDraft.builder()
                        .sectionId("section-1")
                        .heading("背景")
                        .body("Exception: Thread interrupted while sleeping")
                        .build()))
                .composedMarkdown("## 背景\nException: Thread interrupted while sleeping")
                .build();

        assertThatThrownBy(() -> interruptedNodes.writeDocAndSync(new OverAllState(Map.of(
                DocumentStateKeys.TASK_ID, "task-1",
                DocumentStateKeys.DOCUMENT_PLAN, plan,
                DocumentStateKeys.COMPOSED_DRAFT, draft,
                DocumentStateKeys.REVIEW_RESULT, DocumentReviewResult.builder().summary("摘要已生成").build()
        )), RunnableConfig.builder().threadId("task-1").build()).join())
                .isInstanceOf(ExecutionInterruptedException.class);

        verify(larkDocTool, never()).createDoc(anyString(), anyString());
    }
}
