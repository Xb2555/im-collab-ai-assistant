package com.lark.imcollab.harness.document.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.model.entity.ComposedDocumentDraft;
import com.lark.imcollab.common.model.entity.DocumentPlan;
import com.lark.imcollab.common.model.entity.DocumentOutline;
import com.lark.imcollab.common.model.entity.DocumentOutlineSection;
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
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
    void reviewDocRetriesAutoSupplementBeforeWaitingForHumanReview() {
        DocumentExecutionSupport support = mock(DocumentExecutionSupport.class);
        SequencedReviewNodes reviewNodes = new SequencedReviewNodes(
                support,
                bodyNormalizer,
                renderer,
                List.of(
                        """
                        {"backgroundCovered":false,"goalCovered":false,"solutionCovered":false,"risksCovered":true,"ownershipCovered":false,"timelineCovered":true,"missingItems":["项目背景与目标未覆盖","解决方案描述缺失","负责人信息缺失"],"supplementalSections":[],"summary":"发现内容缺项"}
                        """,
                        """
                        {"backgroundCovered":false,"goalCovered":false,"solutionCovered":false,"risksCovered":true,"ownershipCovered":false,"timelineCovered":true,"missingItems":[],"supplementalSections":[{"heading":"项目背景与目标","body":"智能工作流项目聚焦于把群消息中的需求、进展和风险沉淀成可复用汇报材料，目标是让老板快速把握关键决策与当前推进状态。"},{"heading":"解决方案","body":"方案上以 Planner 编排消息检索、文档生成与结果审查，再把结构化结论写入汇报文档，确保内容覆盖项目目标、阶段成果和未决风险。"},{"heading":"负责人与协作","body":"当前由洪徐博牵头推进智能工作流项目，协同 Planner、Harness 与 IM 链路相关同学完成上下文采集、文档生成和后续展示闭环。"}],"summary":"已补齐可自动修复的缺项"}
                        """,
                        """
                        {"backgroundCovered":true,"goalCovered":true,"solutionCovered":true,"risksCovered":true,"ownershipCovered":true,"timelineCovered":true,"missingItems":[],"supplementalSections":[],"summary":"二次复审通过"}
                        """
                )
        );

        Map<String, Object> result = reviewNodes.reviewDoc(new OverAllState(Map.of(
                DocumentStateKeys.TASK_ID, "task-review-1",
                DocumentStateKeys.RAW_INSTRUCTION, "生成智能工作流项目汇报文档（面向老板）",
                DocumentStateKeys.CLARIFIED_INSTRUCTION, "生成智能工作流项目汇报文档（面向老板）",
                DocumentStateKeys.OUTLINE, DocumentOutline.builder()
                        .title("智能工作流项目汇报")
                        .sections(List.of(
                                DocumentOutlineSection.builder().heading("项目背景与目标").build(),
                                DocumentOutlineSection.builder().heading("解决方案").build(),
                                DocumentOutlineSection.builder().heading("负责人与协作").build()
                        ))
                        .build(),
                DocumentStateKeys.SECTION_DRAFTS, List.of(
                        DocumentSectionDraft.builder().heading("项目背景与目标").body("").build(),
                        DocumentSectionDraft.builder().heading("解决方案").body("").build(),
                        DocumentSectionDraft.builder().heading("负责人与协作").body("").build()
                )
        )), RunnableConfig.builder().threadId("task-review-1").build()).join();

        assertThat(result.get(DocumentStateKeys.WAITING_HUMAN_REVIEW)).isEqualTo(false);
        assertThat(result.get(DocumentStateKeys.DONE_REVIEW)).isEqualTo(true);
        DocumentReviewResult reviewResult = (DocumentReviewResult) result.get(DocumentStateKeys.REVIEW_RESULT);
        assertThat(reviewResult.getMissingItems()).isEmpty();
        assertThat(reviewResult.getSummary()).contains("自动补齐重试").contains("二次复审通过");
        @SuppressWarnings("unchecked")
        List<DocumentSectionDraft> drafts = (List<DocumentSectionDraft>) result.get(DocumentStateKeys.SECTION_DRAFTS);
        assertThat(drafts).hasSize(3);
        assertThat(drafts).allMatch(draft -> "SUPPLEMENTED".equals(draft.getStatus()));
        assertThat(drafts.get(0).getBody()).contains("智能工作流项目聚焦于");
        verify(support, never()).publishApprovalRequest(anyString(), any(), anyString());
        assertThat(reviewNodes.reviewCallCount()).isEqualTo(3);
    }

    @Test
    void reviewDocStillWaitsForHumanReviewWhenAutoSupplementRetryCannotFixMissingItems() {
        DocumentExecutionSupport support = mock(DocumentExecutionSupport.class);
        SequencedReviewNodes reviewNodes = new SequencedReviewNodes(
                support,
                bodyNormalizer,
                renderer,
                List.of(
                        """
                        {"backgroundCovered":false,"goalCovered":false,"solutionCovered":false,"risksCovered":true,"ownershipCovered":false,"timelineCovered":true,"missingItems":["项目背景与目标未覆盖","解决方案描述缺失"],"supplementalSections":[],"summary":"发现内容缺项"}
                        """,
                        """
                        {"backgroundCovered":false,"goalCovered":false,"solutionCovered":false,"risksCovered":true,"ownershipCovered":false,"timelineCovered":true,"missingItems":["项目背景与目标未覆盖","解决方案描述缺失"],"supplementalSections":[],"summary":"当前材料不足，仍无法自动补齐"}
                        """
                )
        );

        Map<String, Object> result = reviewNodes.reviewDoc(new OverAllState(Map.of(
                DocumentStateKeys.TASK_ID, "task-review-2",
                DocumentStateKeys.RAW_INSTRUCTION, "生成智能工作流项目汇报文档（面向老板）",
                DocumentStateKeys.CLARIFIED_INSTRUCTION, "生成智能工作流项目汇报文档（面向老板）",
                DocumentStateKeys.OUTLINE, DocumentOutline.builder().title("智能工作流项目汇报").build(),
                DocumentStateKeys.SECTION_DRAFTS, List.of(
                        DocumentSectionDraft.builder().heading("项目背景与目标").body("").build(),
                        DocumentSectionDraft.builder().heading("解决方案").body("").build()
                )
        )), RunnableConfig.builder().threadId("task-review-2").build()).join();

        assertThat(result.get(DocumentStateKeys.WAITING_HUMAN_REVIEW)).isEqualTo(true);
        DocumentReviewResult reviewResult = (DocumentReviewResult) result.get(DocumentStateKeys.REVIEW_RESULT);
        assertThat(reviewResult.getMissingItems()).containsExactly("项目背景与目标未覆盖", "解决方案描述缺失");
        verify(support).publishApprovalRequest(eq("task-review-2"), eq(null), eq("文档审核发现问题：项目背景与目标未覆盖；解决方案描述缺失"));
        assertThat(reviewNodes.reviewCallCount()).isEqualTo(2);
    }

    private static final class SequencedReviewNodes extends DocumentWorkflowNodes {

        private final ArrayDeque<String> reviewResponses;
        private int reviewCallCount;

        private SequencedReviewNodes(
                DocumentExecutionSupport support,
                DocumentBodyNormalizer bodyNormalizer,
                DocumentTemplateRenderer renderer,
                List<String> reviewResponses
        ) {
            super(support, bodyNormalizer, renderer, null, null, null, null, null, null, new ObjectMapper());
            this.reviewResponses = new ArrayDeque<>(reviewResponses);
        }

        @Override
        protected AssistantMessage callAgent(ReactAgent agent, String prompt, String threadId) {
            reviewCallCount++;
            String response = reviewResponses.isEmpty()
                    ? "{\"backgroundCovered\":true,\"goalCovered\":true,\"solutionCovered\":true,\"risksCovered\":true,\"ownershipCovered\":true,\"timelineCovered\":true,\"missingItems\":[],\"supplementalSections\":[],\"summary\":\"默认通过\"}"
                    : reviewResponses.removeFirst();
            return new AssistantMessage(response);
        }

        private int reviewCallCount() {
            return reviewCallCount;
        }
    }
}
