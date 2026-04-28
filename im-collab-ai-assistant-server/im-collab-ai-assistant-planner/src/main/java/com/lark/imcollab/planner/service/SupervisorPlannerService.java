package com.lark.imcollab.planner.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PromptSlotState;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.planner.exception.SupervisorException;
import com.lark.imcollab.planner.prompt.AgentPromptContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class SupervisorPlannerService {

    private final ReactAgent supervisorAgent;
    private final ReactAgent intentAgent;
    private final ReactAgent planningAgent;
    private final PlannerSessionService sessionService;
    private final PlanQualityService qualityService;
    private final TaskRuntimeService taskRuntimeService;

    public SupervisorPlannerService(
            @Qualifier("supervisorAgent") ReactAgent supervisorAgent,
            @Qualifier("intentAgent") ReactAgent intentAgent,
            @Qualifier("planningAgent") ReactAgent planningAgent,
            PlannerSessionService sessionService,
            PlanQualityService qualityService,
            TaskRuntimeService taskRuntimeService
    ) {
        this.supervisorAgent = supervisorAgent;
        this.intentAgent = intentAgent;
        this.planningAgent = planningAgent;
        this.sessionService = sessionService;
        this.qualityService = qualityService;
        this.taskRuntimeService = taskRuntimeService;
    }

    public PlanTaskSession plan(String rawInstruction, WorkspaceContext workspaceContext, String taskId, String userFeedback) {
        String resolvedTaskId = !StrUtil.isEmpty(taskId) ? taskId : UUID.randomUUID().toString();
        PlanTaskSession session = sessionService.getOrCreate(resolvedTaskId);
        session.setTurnCount(session.getTurnCount() + 1);
        mergePersona(session, workspaceContext);

        String prompt = buildPrompt(rawInstruction, workspaceContext, userFeedback, session);
        String extractedContext = extractContext(workspaceContext);
        RunnableConfig supervisorConfig = AgentPromptContext.withPlanningPromptContext(
                RunnableConfig.builder().threadId(resolvedTaskId).build(),
                session,
                rawInstruction,
                extractedContext);
        RunnableConfig intentConfig = AgentPromptContext.withPlanningPromptContext(
                RunnableConfig.builder().threadId(resolvedTaskId + "-intent").build(),
                session,
                rawInstruction,
                extractedContext);
        RunnableConfig planningConfig = AgentPromptContext.withPlanningPromptContext(
                RunnableConfig.builder().threadId(resolvedTaskId + "-planning").build(),
                session,
                rawInstruction,
                extractedContext);

        try {
            AssistantMessage supervisorResponse = supervisorAgent.call(prompt, supervisorConfig);
            String responseText = supervisorResponse.getText();
            if (isCurrentSessionAborted(resolvedTaskId)) {
                return sessionService.get(resolvedTaskId);
            }

            if (needsClarification(responseText, session)) {
                List<String> questions = buildClarificationQuestions(responseText, rawInstruction);
                if (!questions.isEmpty()) {
                    return handleAskUser(session, questions);
                }
            }

            IntentSnapshot intentSnapshot = runIntentUnderstanding(session, prompt, intentConfig, resolvedTaskId);
            String planningPrompt = buildPlanningPrompt(prompt, intentSnapshot);
            return runPlanning(session, planningPrompt, planningConfig, resolvedTaskId);
        } catch (GraphRunnerException e) {
            log.error("Supervisor error for task {}: {}", resolvedTaskId, e.getMessage(), e);
            return failSession(session, e.getMessage());
        }
    }

    public PlanTaskSession adjustPlan(String taskId, String adjustmentInstruction, WorkspaceContext workspaceContext) {
        PlanTaskSession session = sessionService.getOrCreate(taskId);
        if (session.getPlanBlueprint() == null) {
            return plan(adjustmentInstruction, workspaceContext, taskId, null);
        }

        session.setTurnCount(session.getTurnCount() + 1);
        mergePersona(session, workspaceContext);

        String prompt = buildPlanAdjustmentPrompt(session, adjustmentInstruction, workspaceContext);
        String extractedContext = extractContext(workspaceContext);
        RunnableConfig planningConfig = AgentPromptContext.withPlanningPromptContext(
                RunnableConfig.builder().threadId(taskId + "-planning-adjust").build(),
                session,
                adjustmentInstruction,
                extractedContext);

        try {
            Optional<OverAllState> state = planningAgent.invoke(prompt, planningConfig);
            PlanBlueprint blueprint = extractPlanBlueprintFromState(state, taskId, session.getIntentSnapshot())
                    .orElseGet(() -> {
                        try {
                            return fallbackPlanBlueprintByText(prompt, planningConfig, taskId, session.getIntentSnapshot());
                        } catch (GraphRunnerException e) {
                            throw new SupervisorException(e.getMessage());
                        }
                    });
            if (isCurrentSessionAborted(taskId)) {
                return sessionService.get(taskId);
            }
            qualityService.applyPlanAdjustment(session, blueprint, adjustmentInstruction);
            sessionService.save(session);
            taskRuntimeService.projectPlanReady(session, TaskEventTypeEnum.PLAN_ADJUSTED);
            sessionService.publishEvent(taskId, "PLAN_READY");
            return session;
        } catch (Exception e) {
            return failSession(session, e.getMessage());
        }
    }

    public PlanTaskSession interrupt(String taskId) {
        PlanTaskSession session = sessionService.get(taskId);
        session.setPlanningPhase(PlanningPhaseEnum.ABORTED);
        session.setAborted(true);
        session.setTransitionReason("User interrupt");
        sessionService.save(session);
        supervisorAgent.interrupt(RunnableConfig.builder().threadId(taskId).build());
        sessionService.publishEvent(taskId, "ABORTED");
        return session;
    }

    public PlanTaskSession resume(String taskId, String feedback, boolean replanFromRoot) {
        PlanTaskSession session = sessionService.get(taskId);
        session.setAborted(false);
        if (replanFromRoot) {
            session.setClarificationAnswers(null);
            session.setClarificationQuestions(null);
            session.setActivePromptSlots(List.of());
        } else {
            session.setClarificationAnswers(List.of(feedback));
            markPromptSlotsAnswered(session, feedback);
        }
        session.setTransitionReason("Resume: " + feedback);
        session.setVersion(session.getVersion() - 1);
        sessionService.save(session);
        sessionService.publishEvent(taskId, "RESUMED");

        RunnableConfig supervisorConfig = AgentPromptContext.withPlanningPromptContext(
                RunnableConfig.builder().threadId(taskId).build(),
                session,
                feedback,
                "");
        RunnableConfig intentConfig = AgentPromptContext.withPlanningPromptContext(
                RunnableConfig.builder().threadId(taskId + "-intent").build(),
                session,
                feedback,
                "");
        RunnableConfig planningConfig = AgentPromptContext.withPlanningPromptContext(
                RunnableConfig.builder().threadId(taskId + "-planning").build(),
                session,
                feedback,
                "");

        try {
            AssistantMessage supervisorResponse = supervisorAgent.call(feedback, supervisorConfig);
            if (isCurrentSessionAborted(taskId)) {
                return sessionService.get(taskId);
            }
            if (needsClarification(supervisorResponse.getText(), session)) {
                List<String> questions = buildClarificationQuestions(supervisorResponse.getText(), feedback);
                if (!questions.isEmpty()) {
                    return handleAskUser(session, questions);
                }
            }
            IntentSnapshot intentSnapshot = runIntentUnderstanding(session, feedback, intentConfig, taskId);
            return runPlanning(session, buildPlanningPrompt(feedback, intentSnapshot), planningConfig, taskId);
        } catch (Exception e) {
            return failSession(session, e.getMessage());
        }
    }

    private IntentSnapshot runIntentUnderstanding(
            PlanTaskSession session,
            String prompt,
            RunnableConfig config,
            String taskId
    ) {
        try {
            Optional<OverAllState> state = intentAgent.invoke(prompt, config);
            IntentSnapshot intentSnapshot = extractIntentFromState(state)
                    .orElseGet(() -> {
                        try {
                            AssistantMessage response = intentAgent.call(prompt, config);
                            return qualityService.extractIntentSnapshot(response.getText());
                        } catch (GraphRunnerException e) {
                            throw new SupervisorException(e.getMessage());
                        }
                    });
            if (intentSnapshot == null) {
                throw new SupervisorException("Intent understanding returned empty output");
            }
            if (isCurrentSessionAborted(taskId)) {
                throw new SupervisorException("Task was cancelled");
            }
            qualityService.applyIntentReady(session, intentSnapshot);
            sessionService.save(session);
            sessionService.publishEvent(taskId, "INTENT_READY");
            return intentSnapshot;
        } catch (Exception e) {
            throw new SupervisorException(e.getMessage());
        }
    }

    private PlanTaskSession runPlanning(PlanTaskSession session, String prompt, RunnableConfig config, String taskId) {
        try {
            if (isCurrentSessionAborted(taskId)) {
                return sessionService.get(taskId);
            }
            Optional<OverAllState> state = planningAgent.invoke(prompt, config);
            PlanBlueprint blueprint = extractPlanBlueprintFromState(state, taskId, session.getIntentSnapshot())
                    .orElseGet(() -> {
                        try {
                            return fallbackPlanBlueprintByText(prompt, config, taskId, session.getIntentSnapshot());
                        } catch (GraphRunnerException e) {
                            throw new SupervisorException(e.getMessage());
                        }
                    });
            if (isCurrentSessionAborted(taskId)) {
                return sessionService.get(taskId);
            }
            qualityService.applyPlanReady(session, blueprint);
            sessionService.save(session);
            taskRuntimeService.projectPlanReady(session, TaskEventTypeEnum.PLAN_READY);
            sessionService.publishEvent(taskId, "PLAN_READY");
            return session;
        } catch (Exception e) {
            return failSession(session, e.getMessage());
        }
    }

    private Optional<IntentSnapshot> extractIntentFromState(Optional<OverAllState> state) {
        if (state.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> data = state.get().data();
        Object structured = data.get("messages");
        if (structured == null) {
            structured = data.get("message");
        }
        if (structured instanceof IntentSnapshot snapshot) {
            return Optional.of(snapshot);
        }
        if (structured instanceof String jsonText) {
            return Optional.ofNullable(qualityService.extractIntentSnapshot(jsonText));
        }
        return Optional.empty();
    }

    private Optional<PlanBlueprint> extractPlanBlueprintFromState(
            Optional<OverAllState> state,
            String taskId,
            IntentSnapshot intentSnapshot
    ) {
        if (state.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> data = state.get().data();
        Object structured = data.get("messages");
        if (structured == null) {
            structured = data.get("message");
        }
        if (structured instanceof PlanBlueprint blueprint) {
            return Optional.of(qualityService.extractPlanBlueprint(toJson(blueprint), taskId, intentSnapshot));
        }
        if (structured instanceof String jsonText) {
            return Optional.of(qualityService.extractPlanBlueprint(jsonText, taskId, intentSnapshot));
        }
        return Optional.empty();
    }

    private PlanBlueprint fallbackPlanBlueprintByText(
            String prompt,
            RunnableConfig config,
            String taskId,
            IntentSnapshot intentSnapshot
    ) throws GraphRunnerException {
        AssistantMessage plannerResponse = planningAgent.call(prompt, config);
        return qualityService.extractPlanBlueprint(plannerResponse.getText(), taskId, intentSnapshot);
    }

    private void mergePersona(PlanTaskSession session, WorkspaceContext workspaceContext) {
        if (workspaceContext == null) {
            return;
        }
        if (workspaceContext.getProfession() != null && !workspaceContext.getProfession().isBlank()) {
            session.setProfession(workspaceContext.getProfession().trim());
        }
        if (workspaceContext.getIndustry() != null && !workspaceContext.getIndustry().isBlank()) {
            session.setIndustry(workspaceContext.getIndustry().trim());
        }
        if (workspaceContext.getAudience() != null && !workspaceContext.getAudience().isBlank()) {
            session.setAudience(workspaceContext.getAudience().trim());
        }
        if (workspaceContext.getTone() != null && !workspaceContext.getTone().isBlank()) {
            session.setTone(workspaceContext.getTone().trim());
        }
        if (workspaceContext.getLanguage() != null && !workspaceContext.getLanguage().isBlank()) {
            session.setLanguage(workspaceContext.getLanguage().trim());
        }
        if (workspaceContext.getPromptProfile() != null && !workspaceContext.getPromptProfile().isBlank()) {
            session.setPromptProfile(workspaceContext.getPromptProfile().trim());
        }
        if (workspaceContext.getPromptVersion() != null && !workspaceContext.getPromptVersion().isBlank()) {
            session.setPromptVersion(workspaceContext.getPromptVersion().trim());
        }
    }

    private PlanTaskSession handleAskUser(PlanTaskSession session, List<String> questions) {
        session.setClarificationQuestions(questions);
        session.setActivePromptSlots(toPromptSlots(questions));
        session.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        session.setTransitionReason("Information insufficient");
        sessionService.save(session);

        RequireInput requireInput = buildRequireInput(questions);
        sessionService.publishEvent(session.getTaskId(), "ASK_USER", requireInput);
        return session;
    }

    private PlanTaskSession failSession(PlanTaskSession session, String reason) {
        if (session != null && isCurrentSessionAborted(session.getTaskId())) {
            return sessionService.get(session.getTaskId());
        }
        session.setPlanningPhase(PlanningPhaseEnum.FAILED);
        session.setTransitionReason(reason);
        sessionService.save(session);
        sessionService.publishEvent(session.getTaskId(), "FAILED");
        return session;
    }

    private boolean isCurrentSessionAborted(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        try {
            PlanTaskSession current = sessionService.get(taskId);
            return current != null
                    && (current.isAborted() || current.getPlanningPhase() == PlanningPhaseEnum.ABORTED);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private RequireInput buildRequireInput(List<String> questions) {
        String combinedPrompt = String.join("\n", questions);
        return RequireInput.builder()
                .type("TEXT")
                .prompt(combinedPrompt)
                .build();
    }

    private boolean needsClarification(String text, PlanTaskSession session) {
        if (session.getClarificationAnswers() != null && !session.getClarificationAnswers().isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("\"questions\"") || lower.contains("questions:")
                || lower.contains("clarif") || lower.contains("need more");
    }

    private String buildPrompt(String rawInstruction, WorkspaceContext workspaceContext, String userFeedback, PlanTaskSession session) {
        StringBuilder sb = new StringBuilder("User instruction: ").append(rawInstruction);
        if (workspaceContext != null) {
            if (workspaceContext.getSelectedMessages() != null && !workspaceContext.getSelectedMessages().isEmpty()) {
                sb.append("\n\nSelected messages:\n").append(String.join("\n", workspaceContext.getSelectedMessages()));
            } else if (workspaceContext.getTimeRange() != null && !workspaceContext.getTimeRange().isBlank()) {
                sb.append("\n\nTime range: ").append(workspaceContext.getTimeRange());
            }
            if (workspaceContext.getChatId() != null && !workspaceContext.getChatId().isBlank()) {
                sb.append("\nChat ID: ").append(workspaceContext.getChatId());
            }
        }
        if (userFeedback != null && !userFeedback.isBlank()) {
            sb.append("\n\nUser feedback: ").append(userFeedback);
        }
        if (session.getClarificationQuestions() != null && !session.getClarificationQuestions().isEmpty()) {
            sb.append("\n\nPrevious clarification questions: ").append(String.join(" | ", session.getClarificationQuestions()));
        }
        return sb.toString();
    }

    private String buildPlanningPrompt(String prompt, IntentSnapshot intentSnapshot) {
        if (intentSnapshot == null) {
            return prompt;
        }
        return prompt + "\n\nIntent snapshot:\n" + toJson(intentSnapshot);
    }

    private String buildPlanAdjustmentPrompt(
            PlanTaskSession session,
            String adjustmentInstruction,
            WorkspaceContext workspaceContext
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are editing an existing plan, not creating a new task.")
                .append("\nUser change request: ").append(adjustmentInstruction)
                .append("\n\nCurrent plan blueprint JSON:\n").append(toJson(session.getPlanBlueprint()));
        if (session.getIntentSnapshot() != null) {
            builder.append("\n\nCurrent intent snapshot JSON:\n").append(toJson(session.getIntentSnapshot()));
        }
        if (workspaceContext != null && workspaceContext.getSelectedMessages() != null
                && !workspaceContext.getSelectedMessages().isEmpty()) {
            builder.append("\n\nConversation context:\n")
                    .append(String.join("\n", workspaceContext.getSelectedMessages()));
        }
        builder.append("\n\nReturn one complete updated PlanBlueprint JSON object.")
                .append("\nPreserve all unchanged fields from the current plan.")
                .append("\nIf the user asks to add one item, append it instead of replacing existing items.")
                .append("\nDo not rewrite taskBrief into the user's edit instruction unless the user explicitly asks to change the task itself.");
        return builder.toString();
    }

    private String extractContext(WorkspaceContext workspaceContext) {
        if (workspaceContext == null) {
            return "";
        }
        if (workspaceContext.getSelectedMessages() != null && !workspaceContext.getSelectedMessages().isEmpty()) {
            return String.join("\n", workspaceContext.getSelectedMessages());
        }
        if (workspaceContext.getTimeRange() != null && !workspaceContext.getTimeRange().isBlank()) {
            return workspaceContext.getTimeRange();
        }
        return "";
    }

    private List<String> buildClarificationQuestions(String responseText, String userInput) {
        List<String> parsed = extractQuestions(responseText);
        if (parsed.isEmpty()) {
            return parsed;
        }
        return filterRedundantQuestions(parsed, userInput);
    }

    private List<String> filterRedundantQuestions(List<String> questions, String userInput) {
        OutputPreference preference = detectOutputPreference(userInput);
        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        for (String question : questions) {
            if (question == null || question.isBlank()) {
                continue;
            }
            String normalized = question.trim();
            if (preference.isSingleType() && isOutputTypeChoiceQuestion(normalized)) {
                continue;
            }
            deduplicated.add(normalized);
        }
        return new ArrayList<>(deduplicated);
    }

    private OutputPreference detectOutputPreference(String input) {
        if (input == null || input.isBlank()) {
            return OutputPreference.UNKNOWN;
        }
        String normalized = input.toLowerCase();
        boolean hasDoc = containsAny(normalized, "doc", "markdown", "report", "summary", "文档", "纪要");
        boolean hasPpt = containsAny(normalized, "ppt", "slides", "deck", "演示", "汇报");
        if (hasDoc && hasPpt) {
            return OutputPreference.BOTH;
        }
        if (hasDoc) {
            return OutputPreference.DOC_ONLY;
        }
        if (hasPpt) {
            return OutputPreference.PPT_ONLY;
        }
        return OutputPreference.UNKNOWN;
    }

    private boolean isOutputTypeChoiceQuestion(String question) {
        String normalized = question.toLowerCase();
        boolean hasOutputWords = containsAny(normalized, "output", "format", "document", "ppt", "slides", "文档", "输出");
        boolean hasChoiceWords = containsAny(normalized, "or", "both", "还是", "都要");
        return hasOutputWords && hasChoiceWords;
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<String> extractQuestions(String text) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(text);
            if (root.has("questions")) {
                List<String> questions = new ArrayList<>();
                root.get("questions").forEach(n -> {
                    String q = n.asText();
                    if (q != null && !q.isBlank()) {
                        questions.add(q);
                    }
                });
                if (!questions.isEmpty()) {
                    return questions;
                }
            }
            String fallback = text.trim();
            if (!fallback.isBlank() && !fallback.startsWith("{")) {
                return List.of(fallback);
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    private List<PromptSlotState> toPromptSlots(List<String> questions) {
        List<PromptSlotState> slots = new ArrayList<>();
        int index = 1;
        for (String question : questions) {
            slots.add(PromptSlotState.builder()
                    .slotKey("clarification-" + index++)
                    .prompt(question)
                    .value("")
                    .answered(false)
                    .build());
        }
        return slots;
    }

    private void markPromptSlotsAnswered(PlanTaskSession session, String feedback) {
        if (session.getActivePromptSlots() == null || session.getActivePromptSlots().isEmpty()) {
            return;
        }
        session.getActivePromptSlots().forEach(slot -> {
            slot.setValue(feedback);
            slot.setAnswered(true);
        });
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private enum OutputPreference {
        UNKNOWN,
        DOC_ONLY,
        PPT_ONLY,
        BOTH;

        boolean isSingleType() {
            return this == DOC_ONLY || this == PPT_ONLY;
        }
    }
}
