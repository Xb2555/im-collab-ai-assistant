package com.lark.imcollab.harness.scene.c.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.harness.scene.c.model.SceneCDocOutline;
import com.lark.imcollab.harness.scene.c.model.SceneCDocOutlineSection;
import com.lark.imcollab.harness.scene.c.model.SceneCDocReviewResult;
import com.lark.imcollab.harness.scene.c.model.SceneCDocSectionDraft;
import com.lark.imcollab.harness.scene.c.model.SceneCDocWriteResult;
import com.lark.imcollab.harness.scene.c.support.SceneCExecutionSupport;
import com.lark.imcollab.harness.scene.c.template.DocTemplateService;
import com.lark.imcollab.harness.scene.c.template.DocTemplateType;
import com.lark.imcollab.harness.scene.c.workflow.SceneCStateKeys;
import com.lark.imcollab.skills.lark.doc.LarkDocCreateResult;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Component
public class SceneCWorkflowNodes {

    private final SceneCExecutionSupport executionSupport;
    private final DocTemplateService docTemplateService;
    private final ReactAgent sceneCOutlineAgent;
    private final ReactAgent sceneCSectionAgent;
    private final ReactAgent sceneCReviewAgent;
    private final LarkDocTool larkDocTool;
    private final ObjectMapper objectMapper;

    public SceneCWorkflowNodes(
            SceneCExecutionSupport executionSupport,
            DocTemplateService docTemplateService,
            @Qualifier("sceneCOutlineAgent") ReactAgent sceneCOutlineAgent,
            @Qualifier("sceneCSectionAgent") ReactAgent sceneCSectionAgent,
            @Qualifier("sceneCReviewAgent") ReactAgent sceneCReviewAgent,
            LarkDocTool larkDocTool,
            ObjectMapper objectMapper) {
        this.executionSupport = executionSupport;
        this.docTemplateService = docTemplateService;
        this.sceneCOutlineAgent = sceneCOutlineAgent;
        this.sceneCSectionAgent = sceneCSectionAgent;
        this.sceneCReviewAgent = sceneCReviewAgent;
        this.larkDocTool = larkDocTool;
        this.objectMapper = objectMapper;
    }

    public CompletableFuture<Map<String, Object>> dispatchDocTask(OverAllState state, RunnableConfig config) {
        String taskId = state.value(SceneCStateKeys.TASK_ID, "");
        String cardId = state.value(SceneCStateKeys.CARD_ID, "");
        PlanTaskSession session = executionSupport.loadSession(taskId);
        UserPlanCard card = executionSupport.requireCard(session, cardId);
        executionSupport.ensureSceneCTasks(card);
        DocTemplateType templateType = docTemplateService.selectTemplate(card);
        executionSupport.markCardProgress(session, cardId, "RUNNING", 5);
        return CompletableFuture.completedFuture(Map.of(
                SceneCStateKeys.TEMPLATE_TYPE, templateType.name(),
                SceneCStateKeys.WAITING_HUMAN_REVIEW, false
        ));
    }

    public CompletableFuture<Map<String, Object>> generateOutline(OverAllState state, RunnableConfig config) {
        if (state.value(SceneCStateKeys.DONE_OUTLINE, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(SceneCStateKeys.TASK_ID, "");
        String cardId = state.value(SceneCStateKeys.CARD_ID, "");
        PlanTaskSession session = executionSupport.loadSession(taskId);
        UserPlanCard card = executionSupport.requireCard(session, cardId);
        executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, SceneCExecutionSupport.OUTLINE_TASK_SUFFIX), "RUNNING", null);
        executionSupport.publishEvent(taskId, "OUTLINE_GENERATING");
        String prompt = buildOutlinePrompt(session, card, state.value(SceneCStateKeys.TEMPLATE_TYPE, "REPORT"));
        try {
            SceneCDocOutline outline = executionSupport.executeAndEvaluate(
                    taskId,
                    cardId,
                    executionSupport.subtaskId(cardId, SceneCExecutionSupport.OUTLINE_TASK_SUFFIX),
                    () -> invokeOutline(prompt, taskId, cardId),
                    payload -> List.of()
            );
            executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, SceneCExecutionSupport.OUTLINE_TASK_SUFFIX), "COMPLETED", outline.getTitle());
            executionSupport.markCardProgress(session, cardId, "RUNNING", 25);
            executionSupport.publishEvent(taskId, "OUTLINE_READY");
            return CompletableFuture.completedFuture(Map.of(
                    SceneCStateKeys.OUTLINE, outline,
                    SceneCStateKeys.DONE_OUTLINE, true
            ));
        } catch (SceneCExecutionSupport.HumanReviewRequiredException exception) {
            executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, SceneCExecutionSupport.OUTLINE_TASK_SUFFIX), "BLOCKED", "waiting human review");
            executionSupport.publishHumanReview(taskId, exception.getEvaluation().getIssues(), exception.getEvaluation().getSuggestions());
            executionSupport.markCardProgress(session, cardId, "BLOCKED", 25);
            return CompletableFuture.completedFuture(Map.of(
                    SceneCStateKeys.WAITING_HUMAN_REVIEW, true,
                    SceneCStateKeys.HALTED_STAGE, SceneCExecutionSupport.OUTLINE_TASK_SUFFIX
            ));
        }
    }

    public CompletableFuture<Map<String, Object>> generateSections(OverAllState state, RunnableConfig config) {
        if (state.value(SceneCStateKeys.DONE_SECTIONS, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (state.value(SceneCStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(SceneCStateKeys.TASK_ID, "");
        String cardId = state.value(SceneCStateKeys.CARD_ID, "");
        PlanTaskSession session = executionSupport.loadSession(taskId);
        SceneCDocOutline outline = requireValue(state, SceneCStateKeys.OUTLINE, SceneCDocOutline.class);
        executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, SceneCExecutionSupport.SECTIONS_TASK_SUFFIX), "RUNNING", null);
        executionSupport.publishEvent(taskId, "SECTIONS_GENERATING");
        try {
            List<SceneCDocSectionDraft> drafts = executionSupport.executeAndEvaluate(
                    taskId,
                    cardId,
                    executionSupport.subtaskId(cardId, SceneCExecutionSupport.SECTIONS_TASK_SUFFIX),
                    () -> generateSectionDrafts(outline, taskId, cardId),
                    payload -> List.of()
            );
            executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, SceneCExecutionSupport.SECTIONS_TASK_SUFFIX), "COMPLETED", "sections-ready");
            executionSupport.markCardProgress(session, cardId, "RUNNING", 55);
            return CompletableFuture.completedFuture(Map.of(
                    SceneCStateKeys.SECTION_DRAFTS, drafts,
                    SceneCStateKeys.DONE_SECTIONS, true
            ));
        } catch (SceneCExecutionSupport.HumanReviewRequiredException exception) {
            executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, SceneCExecutionSupport.SECTIONS_TASK_SUFFIX), "BLOCKED", "waiting human review");
            executionSupport.publishHumanReview(taskId, exception.getEvaluation().getIssues(), exception.getEvaluation().getSuggestions());
            executionSupport.markCardProgress(session, cardId, "BLOCKED", 55);
            return CompletableFuture.completedFuture(Map.of(
                    SceneCStateKeys.WAITING_HUMAN_REVIEW, true,
                    SceneCStateKeys.HALTED_STAGE, SceneCExecutionSupport.SECTIONS_TASK_SUFFIX
            ));
        }
    }

    public CompletableFuture<Map<String, Object>> reviewDoc(OverAllState state, RunnableConfig config) {
        if (state.value(SceneCStateKeys.DONE_REVIEW, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (state.value(SceneCStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(SceneCStateKeys.TASK_ID, "");
        String cardId = state.value(SceneCStateKeys.CARD_ID, "");
        PlanTaskSession session = executionSupport.loadSession(taskId);
        SceneCDocOutline outline = requireValue(state, SceneCStateKeys.OUTLINE, SceneCDocOutline.class);
        List<SceneCDocSectionDraft> drafts = readSectionDrafts(state);
        executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, SceneCExecutionSupport.REVIEW_TASK_SUFFIX), "RUNNING", null);
        executionSupport.publishEvent(taskId, "REVIEWING");
        try {
            SceneCDocReviewResult reviewResult = executionSupport.executeAndEvaluate(
                    taskId,
                    cardId,
                    executionSupport.subtaskId(cardId, SceneCExecutionSupport.REVIEW_TASK_SUFFIX),
                    () -> reviewAndPatch(outline, drafts, taskId, cardId, state.value(SceneCStateKeys.USER_FEEDBACK, "")),
                    payload -> List.of()
            );
            List<SceneCDocSectionDraft> mergedDrafts = mergeSupplementalSections(drafts, reviewResult.getSupplementalSections());
            executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, SceneCExecutionSupport.REVIEW_TASK_SUFFIX), "COMPLETED", reviewResult.getSummary());
            executionSupport.markCardProgress(session, cardId, "RUNNING", 75);
            return CompletableFuture.completedFuture(Map.of(
                    SceneCStateKeys.REVIEW_RESULT, reviewResult,
                    SceneCStateKeys.SECTION_DRAFTS, mergedDrafts,
                    SceneCStateKeys.DONE_REVIEW, true,
                    SceneCStateKeys.WAITING_HUMAN_REVIEW, false
            ));
        } catch (SceneCExecutionSupport.HumanReviewRequiredException exception) {
            executionSupport.updateSubtask(session, cardId, executionSupport.subtaskId(cardId, SceneCExecutionSupport.REVIEW_TASK_SUFFIX), "BLOCKED", "waiting human review");
            executionSupport.publishHumanReview(taskId, exception.getEvaluation().getIssues(), exception.getEvaluation().getSuggestions());
            executionSupport.markCardProgress(session, cardId, "BLOCKED", 75);
            return CompletableFuture.completedFuture(Map.of(
                    SceneCStateKeys.WAITING_HUMAN_REVIEW, true,
                    SceneCStateKeys.HALTED_STAGE, SceneCExecutionSupport.REVIEW_TASK_SUFFIX
            ));
        }
    }

    public CompletableFuture<Map<String, Object>> writeDocAndSync(OverAllState state, RunnableConfig config) {
        if (state.value(SceneCStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (state.value(SceneCStateKeys.DONE_WRITE, Boolean.FALSE)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String taskId = state.value(SceneCStateKeys.TASK_ID, "");
        String cardId = state.value(SceneCStateKeys.CARD_ID, "");
        String subtaskId = executionSupport.subtaskId(cardId, SceneCExecutionSupport.WRITE_TASK_SUFFIX);
        Optional<com.lark.imcollab.common.model.entity.TaskSubmissionResult> existing = executionSupport.findExistingSubmission(taskId, subtaskId);
        if (existing.isPresent() && existing.get().getArtifactRefs() != null && !existing.get().getArtifactRefs().isEmpty()) {
            return CompletableFuture.completedFuture(Map.of(
                    SceneCStateKeys.ARTIFACT_REFS, existing.get().getArtifactRefs(),
                    SceneCStateKeys.DONE_WRITE, true
            ));
        }
        PlanTaskSession session = executionSupport.loadSession(taskId);
        SceneCDocOutline outline = requireValue(state, SceneCStateKeys.OUTLINE, SceneCDocOutline.class);
        List<SceneCDocSectionDraft> drafts = readSectionDrafts(state);
        SceneCDocReviewResult reviewResult = requireValue(state, SceneCStateKeys.REVIEW_RESULT, SceneCDocReviewResult.class);
        executionSupport.updateSubtask(session, cardId, subtaskId, "RUNNING", null);
        executionSupport.publishEvent(taskId, "DOC_WRITING");
        String markdown = docTemplateService.render(
                DocTemplateType.valueOf(state.value(SceneCStateKeys.TEMPLATE_TYPE, "REPORT")),
                outline,
                drafts,
                reviewResult,
                state.value(SceneCStateKeys.USER_FEEDBACK, "")
        );
        try {
            SceneCDocWriteResult writeResult = executionSupport.executeAndEvaluate(
                    taskId,
                    cardId,
                    subtaskId,
                    () -> createDoc(outline.getTitle(), markdown),
                    SceneCDocWriteResult::getArtifactRefs
            );
            executionSupport.updateSubtask(session, cardId, subtaskId, "COMPLETED", writeResult.getDocUrl());
            executionSupport.setCardArtifacts(session, cardId, writeResult.getArtifactRefs());
            executionSupport.markCardProgress(session, cardId, "COMPLETED", 100);
            session.setTransitionReason("Scene C doc generated");
            plannerSessionCompleted(session);
            executionSupport.saveSession(session);
            executionSupport.publishEvent(taskId, "DOC_COMPLETED");
            return CompletableFuture.completedFuture(Map.of(
                    SceneCStateKeys.DOC_MARKDOWN, markdown,
                    SceneCStateKeys.ARTIFACT_REFS, writeResult.getArtifactRefs(),
                    SceneCStateKeys.DOC_ID, writeResult.getDocId(),
                    SceneCStateKeys.DOC_URL, writeResult.getDocUrl(),
                    SceneCStateKeys.DONE_WRITE, true
            ));
        } catch (SceneCExecutionSupport.HumanReviewRequiredException exception) {
            executionSupport.updateSubtask(session, cardId, subtaskId, "BLOCKED", "waiting human review");
            executionSupport.publishHumanReview(taskId, exception.getEvaluation().getIssues(), exception.getEvaluation().getSuggestions());
            executionSupport.markCardProgress(session, cardId, "BLOCKED", 90);
            return CompletableFuture.completedFuture(Map.of(
                    SceneCStateKeys.WAITING_HUMAN_REVIEW, true,
                    SceneCStateKeys.HALTED_STAGE, SceneCExecutionSupport.WRITE_TASK_SUFFIX
            ));
        }
    }

    public CompletableFuture<String> routeAfterReview(OverAllState state, RunnableConfig config) {
        boolean waiting = state.value(SceneCStateKeys.WAITING_HUMAN_REVIEW, Boolean.FALSE);
        return CompletableFuture.completedFuture(waiting ? StateGraph.END : "write_doc_and_sync");
    }

    public CompletableFuture<String> routeAfterWrite(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(StateGraph.END);
    }

    private SceneCDocOutline invokeOutline(String prompt, String taskId, String cardId) {
        AssistantMessage response = sceneCOutlineAgent.call(prompt, RunnableConfig.builder()
                .threadId(taskId + ":" + cardId + ":outline")
                .build());
        try {
            return objectMapper.readValue(response.getText(), SceneCDocOutline.class);
        } catch (Exception exception) {
            return SceneCDocOutline.builder()
                    .title("文档草稿")
                    .templateType("REPORT")
                    .sections(List.of(
                            SceneCDocOutlineSection.builder().heading("背景").keyPoints(List.of(response.getText())).build()
                    ))
                    .build();
        }
    }

    private List<SceneCDocSectionDraft> generateSectionDrafts(SceneCDocOutline outline, String taskId, String cardId) {
        List<SceneCDocSectionDraft> drafts = new ArrayList<>();
        for (SceneCDocOutlineSection section : outline.getSections()) {
            String prompt = "文档标题：" + outline.getTitle()
                    + "\n章节：" + section.getHeading()
                    + "\n要点：" + String.join("；", section.getKeyPoints());
            AssistantMessage response = sceneCSectionAgent.call(prompt, RunnableConfig.builder()
                    .threadId(taskId + ":" + cardId + ":section:" + normalize(section.getHeading()))
                    .build());
            drafts.add(parseSectionDraft(response.getText(), section));
        }
        return drafts;
    }

    private SceneCDocReviewResult reviewAndPatch(
            SceneCDocOutline outline,
            List<SceneCDocSectionDraft> drafts,
            String taskId,
            String cardId,
            String userFeedback) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("文档标题：").append(outline.getTitle()).append("\n");
        if (userFeedback != null && !userFeedback.isBlank()) {
            prompt.append("人工反馈：").append(userFeedback).append("\n");
        }
        drafts.forEach(section -> prompt.append("## ").append(section.getHeading()).append("\n").append(section.getBody()).append("\n"));
        AssistantMessage response = sceneCReviewAgent.call(prompt.toString(), RunnableConfig.builder()
                .threadId(taskId + ":" + cardId + ":review")
                .build());
        try {
            return objectMapper.readValue(response.getText(), SceneCDocReviewResult.class);
        } catch (Exception exception) {
            return SceneCDocReviewResult.builder()
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

    private SceneCDocWriteResult createDoc(String title, String markdown) {
        LarkDocCreateResult result = larkDocTool.createDoc(title, markdown);
        return SceneCDocWriteResult.builder()
                .docId(result.getDocId())
                .docUrl(result.getDocUrl())
                .artifactRefs(List.of(result.getDocId(), result.getDocUrl()))
                .build();
    }

    private SceneCDocSectionDraft parseSectionDraft(String text, SceneCDocOutlineSection section) {
        try {
            return objectMapper.readValue(text, SceneCDocSectionDraft.class);
        } catch (Exception exception) {
            return SceneCDocSectionDraft.builder()
                    .heading(section.getHeading())
                    .body(text)
                    .build();
        }
    }

    private List<SceneCDocSectionDraft> mergeSupplementalSections(
            List<SceneCDocSectionDraft> drafts,
            List<SceneCDocSectionDraft> supplementalSections) {
        if (supplementalSections == null || supplementalSections.isEmpty()) {
            return drafts;
        }
        List<SceneCDocSectionDraft> merged = new ArrayList<>(drafts);
        merged.addAll(supplementalSections);
        return merged;
    }

    private List<SceneCDocSectionDraft> readSectionDrafts(OverAllState state) {
        Object raw = state.data().get(SceneCStateKeys.SECTION_DRAFTS);
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
}
