package com.lark.imcollab.harness.document.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.model.entity.DocumentOutline;
import com.lark.imcollab.common.model.entity.DocumentOutlineSection;
import com.lark.imcollab.common.model.entity.DocumentReviewResult;
import com.lark.imcollab.common.model.entity.DocumentSectionDraft;
import com.lark.imcollab.harness.document.support.DocumentExecutionSupport;
import com.lark.imcollab.harness.document.template.DocumentTemplateService;
import com.lark.imcollab.harness.document.template.DocumentTemplateType;
import com.lark.imcollab.harness.document.workflow.DocumentStateKeys;
import com.lark.imcollab.skills.lark.doc.LarkDocCreateResult;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class DocumentWorkflowNodes {

    private final DocumentExecutionSupport support;
    private final DocumentTemplateService templateService;
    private final ReactAgent documentOutlineAgent;
    private final ReactAgent documentSectionAgent;
    private final ReactAgent documentReviewAgent;
    private final LarkDocTool larkDocTool;
    private final ObjectMapper objectMapper;

    public DocumentWorkflowNodes(
            DocumentExecutionSupport support,
            DocumentTemplateService templateService,
            @Qualifier("documentOutlineAgent") ReactAgent documentOutlineAgent,
            @Qualifier("documentSectionAgent") ReactAgent documentSectionAgent,
            @Qualifier("documentReviewAgent") ReactAgent documentReviewAgent,
            LarkDocTool larkDocTool,
            ObjectMapper objectMapper) {
        this.support = support;
        this.templateService = templateService;
        this.documentOutlineAgent = documentOutlineAgent;
        this.documentSectionAgent = documentSectionAgent;
        this.documentReviewAgent = documentReviewAgent;
        this.larkDocTool = larkDocTool;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<Map<String, Object>> dispatchDocTask(OverAllState state, RunnableConfig config) {
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        support.loadTask(taskId);
        support.publishEvent(taskId, null, TaskEventType.STEP_STARTED);
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.TEMPLATE_TYPE, DocumentTemplateType.REPORT.name(),
                DocumentStateKeys.WAITING_HUMAN_REVIEW, false
        ));
    }

    public CompletableFuture<Map<String, Object>> generateOutline(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.DONE_OUTLINE, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        String userFeedback = state.value(DocumentStateKeys.USER_FEEDBACK, "");
        String prompt = "生成文档大纲。任务ID：" + taskId + (userFeedback.isBlank() ? "" : "\n用户反馈：" + userFeedback);
        DocumentOutline outline = invokeOutline(prompt, taskId);
        support.saveArtifact(taskId, support.subtaskId(taskId, DocumentExecutionSupport.OUTLINE_TASK_SUFFIX),
                ArtifactType.DOC_OUTLINE, outline.getTitle(), support.writeJson(outline), null);
        support.publishEvent(taskId, null, TaskEventType.STEP_COMPLETED);
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.OUTLINE, outline,
                DocumentStateKeys.DONE_OUTLINE, true
        ));
    }

    public CompletableFuture<Map<String, Object>> generateSections(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.DONE_SECTIONS, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        DocumentOutline outline = requireValue(state, DocumentStateKeys.OUTLINE, DocumentOutline.class);
        List<DocumentSectionDraft> drafts = new ArrayList<>();
        for (DocumentOutlineSection section : outline.getSections()) {
            String prompt = "文档标题：" + outline.getTitle() + "\n章节：" + section.getHeading()
                    + "\n要点：" + String.join("；", section.getKeyPoints());
            AssistantMessage response = callAgent(documentSectionAgent, prompt, taskId + ":section:" + section.getHeading());
            drafts.add(parseSectionDraft(response.getText(), section));
        }
        support.publishEvent(taskId, null, TaskEventType.STEP_COMPLETED);
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.SECTION_DRAFTS, drafts,
                DocumentStateKeys.DONE_SECTIONS, true
        ));
    }

    public CompletableFuture<Map<String, Object>> reviewDoc(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.DONE_REVIEW, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        String userFeedback = state.value(DocumentStateKeys.USER_FEEDBACK, "");
        DocumentOutline outline = requireValue(state, DocumentStateKeys.OUTLINE, DocumentOutline.class);
        List<DocumentSectionDraft> drafts = readSectionDrafts(state);
        StringBuilder prompt = new StringBuilder("文档标题：").append(outline.getTitle()).append("\n");
        if (!userFeedback.isBlank()) prompt.append("人工反馈：").append(userFeedback).append("\n");
        drafts.forEach(s -> prompt.append("## ").append(s.getHeading()).append("\n").append(s.getBody()).append("\n"));
        AssistantMessage response = callAgent(documentReviewAgent, prompt.toString(), taskId + ":review");
        DocumentReviewResult reviewResult = parseReviewResult(response.getText());

        boolean needsHumanReview = reviewResult.getMissingItems() != null && !reviewResult.getMissingItems().isEmpty();
        if (needsHumanReview && userFeedback.isBlank()) {
            support.publishApprovalRequest(taskId, null, "文档审核发现问题：" + String.join("；", reviewResult.getMissingItems()));
            return CompletableFuture.completedFuture(Map.of(
                    DocumentStateKeys.WAITING_HUMAN_REVIEW, true,
                    DocumentStateKeys.REVIEW_RESULT, reviewResult
            ));
        }
        support.publishEvent(taskId, null, TaskEventType.STEP_COMPLETED);
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.REVIEW_RESULT, reviewResult,
                DocumentStateKeys.DONE_REVIEW, true,
                DocumentStateKeys.WAITING_HUMAN_REVIEW, false
        ));
    }

    public CompletableFuture<Map<String, Object>> writeDocAndSync(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        DocumentOutline outline = requireValue(state, DocumentStateKeys.OUTLINE, DocumentOutline.class);
        List<DocumentSectionDraft> drafts = readSectionDrafts(state);
        DocumentReviewResult reviewResult = requireValue(state, DocumentStateKeys.REVIEW_RESULT, DocumentReviewResult.class);
        String markdown = templateService.render(
                DocumentTemplateType.valueOf(state.value(DocumentStateKeys.TEMPLATE_TYPE, "REPORT")),
                outline, drafts, reviewResult,
                state.value(DocumentStateKeys.USER_FEEDBACK, "")
        );
        LarkDocCreateResult result = larkDocTool.createDoc(outline.getTitle(), markdown);
        support.saveArtifact(taskId, support.subtaskId(taskId, DocumentExecutionSupport.WRITE_TASK_SUFFIX),
                ArtifactType.DOC_LINK, outline.getTitle(), null, result.getDocUrl());
        support.publishEvent(taskId, null, TaskEventType.ARTIFACT_CREATED);
        support.publishEvent(taskId, null, TaskEventType.TASK_COMPLETED);
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.DOC_URL, result.getDocUrl(),
                DocumentStateKeys.DOC_ID, result.getDocId(),
                DocumentStateKeys.DONE_WRITE, true
        ));
    }

    public CompletableFuture<String> routeAfterReview(OverAllState state, RunnableConfig config) {
        boolean waiting = Boolean.TRUE.equals(state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE));
        return CompletableFuture.completedFuture(waiting ? StateGraph.END : "write_doc_and_sync");
    }

    public CompletableFuture<String> routeAfterWrite(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(StateGraph.END);
    }

    private DocumentOutline invokeOutline(String prompt, String taskId) {
        AssistantMessage response = callAgent(documentOutlineAgent, prompt, taskId + ":outline");
        try {
            return objectMapper.readValue(response.getText(), DocumentOutline.class);
        } catch (Exception e) {
            return DocumentOutline.builder()
                    .title("文档草稿")
                    .templateType("REPORT")
                    .sections(List.of(DocumentOutlineSection.builder()
                            .heading("背景").keyPoints(List.of(response.getText())).build()))
                    .build();
        }
    }

    private DocumentSectionDraft parseSectionDraft(String text, DocumentOutlineSection section) {
        try {
            return objectMapper.readValue(text, DocumentSectionDraft.class);
        } catch (Exception e) {
            return DocumentSectionDraft.builder().heading(section.getHeading()).body(text).build();
        }
    }

    private DocumentReviewResult parseReviewResult(String text) {
        try {
            return objectMapper.readValue(text, DocumentReviewResult.class);
        } catch (Exception e) {
            return DocumentReviewResult.builder()
                    .backgroundCovered(true).goalCovered(true).solutionCovered(true)
                    .risksCovered(true).ownershipCovered(true).timelineCovered(true)
                    .missingItems(List.of()).supplementalSections(List.of()).summary(text).build();
        }
    }

    protected AssistantMessage callAgent(ReactAgent agent, String prompt, String threadId) {
        try {
            return agent.call(prompt, RunnableConfig.builder().threadId(threadId).build());
        } catch (Exception e) {
            throw new IllegalStateException("Agent call failed for thread: " + threadId, e);
        }
    }

    private List<DocumentSectionDraft> readSectionDrafts(OverAllState state) {
        Object raw = state.data().get(DocumentStateKeys.SECTION_DRAFTS);
        if (raw == null) return List.of();
        return objectMapper.convertValue(raw, new TypeReference<>() {});
    }

    private <T> T requireValue(OverAllState state, String key, Class<T> type) {
        Object raw = state.data().get(key);
        if (raw == null) throw new IllegalStateException("Missing state value: " + key);
        return objectMapper.convertValue(raw, type);
    }
}
