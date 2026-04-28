package com.lark.imcollab.harness.document.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.DocumentOutline;
import com.lark.imcollab.common.model.entity.DocumentOutlineSection;
import com.lark.imcollab.common.model.entity.DocumentReviewResult;
import com.lark.imcollab.common.model.entity.DocumentSectionDraft;
import com.lark.imcollab.common.model.entity.DocumentWriteResult;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
public class DocumentWorkflowNodes {

    private final DocumentExecutionSupport executionSupport;
    private final DocumentTemplateService templateService;
    private final ReactAgent documentOutlineAgent;
    private final ReactAgent documentSectionAgent;
    private final ReactAgent documentReviewAgent;
    private final LarkDocTool larkDocTool;
    private final ObjectMapper objectMapper;

    public DocumentWorkflowNodes(
            DocumentExecutionSupport executionSupport,
            DocumentTemplateService templateService,
            @Qualifier("documentOutlineAgent") ReactAgent documentOutlineAgent,
            @Qualifier("documentSectionAgent") ReactAgent documentSectionAgent,
            @Qualifier("documentReviewAgent") ReactAgent documentReviewAgent,
            LarkDocTool larkDocTool,
            ObjectMapper objectMapper) {
        this.executionSupport = executionSupport;
        this.templateService = templateService;
        this.documentOutlineAgent = documentOutlineAgent;
        this.documentSectionAgent = documentSectionAgent;
        this.documentReviewAgent = documentReviewAgent;
        this.larkDocTool = larkDocTool;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<Map<String, Object>> dispatchDocTask(OverAllState state, RunnableConfig config) {
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        String cardId = state.value(DocumentStateKeys.CARD_ID, "");
        PlanTaskSession session = executionSupport.loadSession(taskId);
        UserPlanCard card = executionSupport.requireCard(session, cardId);
        executionSupport.ensureDocumentTasks(card);
        DocumentTemplateType templateType = templateService.selectTemplate(card);
        executionSupport.markCardProgress(session, cardId, "RUNNING", 5);
        return CompletableFuture.completedFuture(Map.of(
                DocumentStateKeys.TEMPLATE_TYPE, templateType.name(),
                DocumentStateKeys.WAITING_HUMAN_REVIEW, false
        ));
    }

    public CompletableFuture<Map<String, Object>> generateOutline(OverAllState state, RunnableConfig config) {
        if (state.value(DocumentStateKeys.DONE_OUTLINE, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        String cardId = state.value(DocumentStateKeys.CARD_ID, "");
        PlanTaskSession session = executionSupport.loadSession(taskId);
        UserPlanCard card = executionSupport.requireCard(session, cardId);
        executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, DocumentExecutionSupport.OUTLINE_TASK_SUFFIX), "RUNNING", null);
        executionSupport.publishEvent(taskId, "OUTLINE_GENERATING");
        String prompt = buildOutlinePrompt(session, card, state.value(DocumentStateKeys.TEMPLATE_TYPE, "REPORT"));
        try {
            DocumentOutline outline = executionSupport.executeAndEvaluate(
                    taskId,
                    cardId,
                    executionSupport.subtaskId(cardId, DocumentExecutionSupport.OUTLINE_TASK_SUFFIX),
                    () -> invokeOutline(prompt, taskId, cardId),
                    payload -> List.of(),
                    session
            );
            executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, DocumentExecutionSupport.OUTLINE_TASK_SUFFIX), "COMPLETED", outline.getTitle());
            executionSupport.markCardProgress(session, cardId, "RUNNING", 25);
            executionSupport.publishEvent(taskId, "OUTLINE_READY");
            return CompletableFuture.completedFuture(Map.of(
                    DocumentStateKeys.OUTLINE, outline,
                    DocumentStateKeys.DONE_OUTLINE, true
            ));
        } catch (DocumentExecutionSupport.HumanReviewRequiredException exception) {
            executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, DocumentExecutionSupport.OUTLINE_TASK_SUFFIX), "BLOCKED", "waiting human review");
            executionSupport.publishHumanReview(taskId, exception.getEvaluation().getIssues(), exception.getEvaluation().getSuggestions());
            executionSupport.markCardProgress(session, cardId, "BLOCKED", 25);
            return CompletableFuture.completedFuture(Map.of(
                    DocumentStateKeys.WAITING_HUMAN_REVIEW, true,
                    DocumentStateKeys.HALTED_STAGE, DocumentExecutionSupport.OUTLINE_TASK_SUFFIX
            ));
        }
    }

    public CompletableFuture<Map<String, Object>> generateSections(OverAllState state, RunnableConfig config) {
        if (state.value(DocumentStateKeys.DONE_SECTIONS, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        String cardId = state.value(DocumentStateKeys.CARD_ID, "");
        PlanTaskSession session = executionSupport.loadSession(taskId);
        DocumentOutline outline = requireValue(state, DocumentStateKeys.OUTLINE, DocumentOutline.class);
        UserPlanCard card = executionSupport.requireCard(session, cardId);
        executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, DocumentExecutionSupport.SECTIONS_TASK_SUFFIX), "RUNNING", null);
        executionSupport.publishEvent(taskId, "SECTIONS_GENERATING");
        List<DocumentSectionDraft> existingDrafts = readSectionDrafts(state);
        LinkedHashSet<String> completedKeys = new LinkedHashSet<>(readCompletedSectionKeys(state));
        for (DocumentOutlineSection section : outline.getSections()) {
            executionSupport.ensureSectionTask(session, card, normalize(section.getHeading()), section.getHeading());
        }
        try {
            List<DocumentSectionDraft> drafts = generateSectionDrafts(
                    session,
                    cardId,
                    outline,
                    taskId,
                    existingDrafts,
                    completedKeys
            );
            executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, DocumentExecutionSupport.SECTIONS_TASK_SUFFIX), "COMPLETED", "sections-ready");
            executionSupport.markCardProgress(session, cardId, "RUNNING", 55);
            return CompletableFuture.completedFuture(Map.of(
                    DocumentStateKeys.SECTION_DRAFTS, drafts,
                    DocumentStateKeys.COMPLETED_SECTION_KEYS, new ArrayList<>(completedKeys),
                    DocumentStateKeys.DONE_SECTIONS, true
            ));
        } catch (DocumentExecutionSupport.HumanReviewRequiredException exception) {
            executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, DocumentExecutionSupport.SECTIONS_TASK_SUFFIX), "BLOCKED", "waiting human review");
            executionSupport.publishHumanReview(taskId, exception.getEvaluation().getIssues(), exception.getEvaluation().getSuggestions());
            executionSupport.markCardProgress(session, cardId, "BLOCKED", 55);
            return CompletableFuture.completedFuture(Map.of(
                    DocumentStateKeys.SECTION_DRAFTS, existingDrafts,
                    DocumentStateKeys.COMPLETED_SECTION_KEYS, new ArrayList<>(completedKeys),
                    DocumentStateKeys.WAITING_HUMAN_REVIEW, true,
                    DocumentStateKeys.HALTED_STAGE, DocumentExecutionSupport.SECTIONS_TASK_SUFFIX
            ));
        }
    }

    public CompletableFuture<Map<String, Object>> reviewDoc(OverAllState state, RunnableConfig config) {
        if (state.value(DocumentStateKeys.DONE_REVIEW, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        String cardId = state.value(DocumentStateKeys.CARD_ID, "");
        PlanTaskSession session = executionSupport.loadSession(taskId);
        DocumentOutline outline = requireValue(state, DocumentStateKeys.OUTLINE, DocumentOutline.class);
        List<DocumentSectionDraft> drafts = readSectionDrafts(state);
        executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, DocumentExecutionSupport.REVIEW_TASK_SUFFIX), "RUNNING", null);
        executionSupport.publishEvent(taskId, "REVIEWING");
        try {
            DocumentReviewResult reviewResult = executionSupport.executeAndEvaluate(
                    taskId,
                    cardId,
                    executionSupport.subtaskId(cardId, DocumentExecutionSupport.REVIEW_TASK_SUFFIX),
                    () -> reviewAndPatch(outline, drafts, taskId, cardId, state.value(DocumentStateKeys.USER_FEEDBACK, "")),
                    payload -> List.of(),
                    session
            );
            List<DocumentSectionDraft> mergedDrafts = mergeSupplementalSections(drafts, reviewResult.getSupplementalSections());
            executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, DocumentExecutionSupport.REVIEW_TASK_SUFFIX), "COMPLETED", reviewResult.getSummary());
            executionSupport.markCardProgress(session, cardId, "RUNNING", 75);
            return CompletableFuture.completedFuture(Map.of(
                    DocumentStateKeys.REVIEW_RESULT, reviewResult,
                    DocumentStateKeys.SECTION_DRAFTS, mergedDrafts,
                    DocumentStateKeys.DONE_REVIEW, true,
                    DocumentStateKeys.WAITING_HUMAN_REVIEW, false
            ));
        } catch (DocumentExecutionSupport.HumanReviewRequiredException exception) {
            executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, DocumentExecutionSupport.REVIEW_TASK_SUFFIX), "BLOCKED", "waiting human review");
            executionSupport.publishHumanReview(taskId, exception.getEvaluation().getIssues(), exception.getEvaluation().getSuggestions());
            executionSupport.markCardProgress(session, cardId, "BLOCKED", 75);
            return CompletableFuture.completedFuture(Map.of(
                    DocumentStateKeys.WAITING_HUMAN_REVIEW, true,
                    DocumentStateKeys.HALTED_STAGE, DocumentExecutionSupport.REVIEW_TASK_SUFFIX
            ));
        }
    }

    public CompletableFuture<Map<String, Object>> writeDocAndSync(OverAllState state, RunnableConfig config) {
        if (state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (state.value(DocumentStateKeys.DONE_WRITE, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(DocumentStateKeys.TASK_ID, "");
        String cardId = state.value(DocumentStateKeys.CARD_ID, "");
        String subtaskId = executionSupport.subtaskId(cardId, DocumentExecutionSupport.WRITE_TASK_SUFFIX);
        Optional<com.lark.imcollab.common.model.entity.TaskSubmissionResult> existing = executionSupport.findExistingSubmission(taskId, subtaskId);
        if (existing.isPresent() && existing.get().getArtifactRefs() != null && !existing.get().getArtifactRefs().isEmpty()) {
            return CompletableFuture.completedFuture(Map.of(
                    DocumentStateKeys.ARTIFACT_REFS, existing.get().getArtifactRefs(),
                    DocumentStateKeys.DONE_WRITE, true
            ));
        }
        PlanTaskSession session = executionSupport.loadSession(taskId);
        DocumentOutline outline = requireValue(state, DocumentStateKeys.OUTLINE, DocumentOutline.class);
        List<DocumentSectionDraft> drafts = readSectionDrafts(state);
        DocumentReviewResult reviewResult = requireValue(state, DocumentStateKeys.REVIEW_RESULT, DocumentReviewResult.class);
        executionSupport.updateSubtask(session, cardId, subtaskId, "RUNNING", null);
        executionSupport.publishEvent(taskId, "DOC_WRITING");
        String markdown = templateService.render(
                DocumentTemplateType.valueOf(state.value(DocumentStateKeys.TEMPLATE_TYPE, "REPORT")),
                outline,
                drafts,
                reviewResult,
                state.value(DocumentStateKeys.USER_FEEDBACK, "")
        );
        try {
            DocumentWriteResult writeResult = executionSupport.executeAndEvaluate(
                    taskId,
                    cardId,
                    subtaskId,
                    () -> createDoc(outline.getTitle(), markdown),
                    DocumentWriteResult::getArtifactRefs,
                    session
            );
            executionSupport.updateSubtask(session, cardId, subtaskId, "COMPLETED", writeResult.getDocUrl());
            executionSupport.setCardArtifacts(session, cardId, writeResult.getArtifactRefs());
            executionSupport.markCardProgress(session, cardId, "COMPLETED", 100);
            session.setTransitionReason("Document generated");
            plannerSessionCompleted(session);
            executionSupport.saveSession(session);
            executionSupport.publishEvent(taskId, "DOC_COMPLETED");
            return CompletableFuture.completedFuture(Map.of(
                    DocumentStateKeys.DOC_MARKDOWN, markdown,
                    DocumentStateKeys.ARTIFACT_REFS, writeResult.getArtifactRefs(),
                    DocumentStateKeys.DOC_ID, writeResult.getDocId(),
                    DocumentStateKeys.DOC_URL, writeResult.getDocUrl(),
                    DocumentStateKeys.DONE_WRITE, true
            ));
        } catch (DocumentExecutionSupport.HumanReviewRequiredException exception) {
            executionSupport.updateSubtask(session, cardId, subtaskId, "BLOCKED", "waiting human review");
            executionSupport.publishHumanReview(taskId, exception.getEvaluation().getIssues(), exception.getEvaluation().getSuggestions());
            executionSupport.markCardProgress(session, cardId, "BLOCKED", 90);
            return CompletableFuture.completedFuture(Map.of(
                    DocumentStateKeys.WAITING_HUMAN_REVIEW, true,
                    DocumentStateKeys.HALTED_STAGE, DocumentExecutionSupport.WRITE_TASK_SUFFIX
            ));
        }
    }

    public CompletableFuture<String> routeAfterReview(OverAllState state, RunnableConfig config) {
        boolean waiting = state.value(DocumentStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE);
        return CompletableFuture.completedFuture(waiting ? StateGraph.END : "write_doc_and_sync");
    }

    public CompletableFuture<String> routeAfterWrite(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(StateGraph.END);
    }

    private DocumentOutline invokeOutline(String prompt, String taskId, String cardId) {
        AssistantMessage response = callAgent(
                documentOutlineAgent,
                prompt,
                taskId + ":" + cardId + ":outline"
        );
        try {
            return objectMapper.readValue(response.getText(), DocumentOutline.class);
        } catch (Exception exception) {
            return DocumentOutline.builder()
                    .title("文档草稿")
                    .templateType("REPORT")
                    .sections(List.of(
                            DocumentOutlineSection.builder().heading("背景").keyPoints(List.of(response.getText())).build()
                    ))
                    .build();
        }
    }

    private List<DocumentSectionDraft> generateSectionDrafts(
            PlanTaskSession session,
            String cardId,
            DocumentOutline outline,
            String taskId,
            List<DocumentSectionDraft> existingDrafts,
            LinkedHashSet<String> completedKeys) {
        List<DocumentSectionDraft> drafts = new ArrayList<>(existingDrafts);
        for (DocumentOutlineSection section : outline.getSections()) {
            String sectionKey = normalize(section.getHeading());
            if (completedKeys.contains(sectionKey)) {
                continue;
            }
            String subtaskId = executionSupport.sectionSubtaskId(cardId, sectionKey);
            executionSupport.updateSubtask(session, cardId, subtaskId, "RUNNING", null);
            String prompt = "文档标题：" + outline.getTitle()
                    + "\n章节：" + section.getHeading()
                    + "\n要点：" + String.join("；", section.getKeyPoints());
            DocumentSectionDraft draft = executionSupport.executeAndEvaluate(
                    taskId,
                    cardId,
                    subtaskId,
                    () -> {
                        AssistantMessage response = callAgent(
                                documentSectionAgent,
                                prompt,
                                taskId + ":" + cardId + ":section:" + sectionKey
                        );
                        return parseSectionDraft(response.getText(), section);
                    },
                    payload -> List.of(),
                    session
            );
            replaceDraft(drafts, draft);
            completedKeys.add(sectionKey);
            executionSupport.updateSubtask(session, cardId, subtaskId, "COMPLETED", draft.getHeading());
        }
        return drafts;
    }

    private DocumentReviewResult reviewAndPatch(
            DocumentOutline outline,
            List<DocumentSectionDraft> drafts,
            String taskId,
            String cardId,
            String userFeedback) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("文档标题：").append(outline.getTitle()).append("\n");
        if (userFeedback != null && !userFeedback.isBlank()) {
            prompt.append("人工反馈：").append(userFeedback).append("\n");
        }
        drafts.forEach(section -> prompt.append("## ").append(section.getHeading()).append("\n").append(section.getBody()).append("\n"));
        AssistantMessage response = callAgent(
                documentReviewAgent,
                prompt.toString(),
                taskId + ":" + cardId + ":review"
        );
        try {
            return objectMapper.readValue(response.getText(), DocumentReviewResult.class);
        } catch (Exception exception) {
            return DocumentReviewResult.builder()
                    .backgroundCovered(true)
                    .goalCovered(true)
                    .solutionCovered(true)
                    .risksCovered(true)
                    .ownershipCovered(true)
                    .timelineCovered(true)
                    .missingItems(List.of())
                    .supplementalSections(List.of())
                    .summary(response.getText())
                    .build();
        }
    }

    private AssistantMessage callAgent(ReactAgent agent, String prompt, String threadId) {
        try {
            return agent.call(prompt, RunnableConfig.builder().threadId(threadId).build());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to invoke agent for thread: " + threadId, exception);
        }
    }

    private DocumentWriteResult createDoc(String title, String markdown) {
        LarkDocCreateResult result = larkDocTool.createDoc(title, markdown);
        return DocumentWriteResult.builder()
                .docId(result.getDocId())
                .docUrl(result.getDocUrl())
                .artifactRefs(List.of(result.getDocId(), result.getDocUrl()))
                .build();
    }

    private DocumentSectionDraft parseSectionDraft(String text, DocumentOutlineSection section) {
        try {
            return objectMapper.readValue(text, DocumentSectionDraft.class);
        } catch (Exception exception) {
            return DocumentSectionDraft.builder()
                    .heading(section.getHeading())
                    .body(text)
                    .build();
        }
    }

    private List<DocumentSectionDraft> mergeSupplementalSections(
            List<DocumentSectionDraft> drafts,
            List<DocumentSectionDraft> supplementalSections) {
        if (supplementalSections == null || supplementalSections.isEmpty()) {
            return drafts;
        }
        List<DocumentSectionDraft> merged = new ArrayList<>(drafts);
        merged.addAll(supplementalSections);
        return merged;
    }

    private List<DocumentSectionDraft> readSectionDrafts(OverAllState state) {
        Object raw = state.data().get(DocumentStateKeys.SECTION_DRAFTS);
        if (raw == null) {
            return List.of();
        }
        return objectMapper.convertValue(raw, new TypeReference<>() {});
    }

    private List<String> readCompletedSectionKeys(OverAllState state) {
        Object raw = state.data().get(DocumentStateKeys.COMPLETED_SECTION_KEYS);
        if (raw == null) {
            return List.of();
        }
        return objectMapper.convertValue(raw, new TypeReference<>() {});
    }

    private <T> T requireValue(OverAllState state, String key, Class<T> type) {
        Object raw = state.data().get(key);
        if (raw == null) {
            throw new IllegalStateException("Missing state value: " + key);
        }
        return objectMapper.convertValue(raw, type);
    }

    private void plannerSessionCompleted(PlanTaskSession session) {
        boolean allCompleted = session.getPlanCards().stream().allMatch(card -> "COMPLETED".equals(card.getStatus()) || "BLOCKED".equals(card.getStatus()));
        if (allCompleted) {
            session.setPlanningPhase(com.lark.imcollab.common.model.enums.PlanningPhaseEnum.COMPLETED);
        }
    }

    private String buildOutlinePrompt(PlanTaskSession session, UserPlanCard card, String templateType) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("任务标题：").append(card.getTitle()).append("\n");
        prompt.append("任务描述：").append(card.getDescription()).append("\n");
        prompt.append("模板类型：").append(templateType).append("\n");
        prompt.append("受众：").append(session.getAudience()).append("\n");
        prompt.append("风格：").append(session.getTone()).append("\n");
        prompt.append("语言：").append(session.getLanguage()).append("\n");
        return prompt.toString();
    }

    private String normalize(String input) {
        return input == null ? "section" : input.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]+", "-");
    }

    private void replaceDraft(List<DocumentSectionDraft> drafts, DocumentSectionDraft draft) {
        List<DocumentSectionDraft> kept = drafts.stream()
                .filter(existing -> existing.getHeading() == null || !existing.getHeading().equals(draft.getHeading()))
                .collect(Collectors.toCollection(ArrayList::new));
        kept.add(draft);
        drafts.clear();
        drafts.addAll(kept);
    }
}
