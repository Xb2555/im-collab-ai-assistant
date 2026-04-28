package com.lark.imcollab.planner.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.lark.imcollab.common.model.entity.PlanCardsOutput;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
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

@Deprecated
@Slf4j
public class SupervisorPlannerService {

    private final ReactAgent supervisorAgent;
    private final ReactAgent planningAgent;
    private final PlannerSessionService sessionService;
    private final PlanQualityService qualityService;

    public SupervisorPlannerService(
            @Qualifier("supervisorAgent") ReactAgent supervisorAgent,
            @Qualifier("planningAgent") ReactAgent planningAgent,
            PlannerSessionService sessionService,
            PlanQualityService qualityService) {
        this.supervisorAgent = supervisorAgent;
        this.planningAgent = planningAgent;
        this.sessionService = sessionService;
        this.qualityService = qualityService;
    }

    public PlanTaskSession plan(String rawInstruction, WorkspaceContext workspaceContext, String taskId, String userFeedback) {
        String resolvedTaskId = !StrUtil.isEmpty(taskId) ? taskId : UUID.randomUUID().toString();
        PlanTaskSession session = sessionService.getOrCreate(resolvedTaskId);
        session.setTurnCount(session.getTurnCount() + 1);
        mergePersona(session, workspaceContext);

        String prompt = buildPrompt(rawInstruction, workspaceContext, userFeedback, session);

        // Supervisor 的执行线程，用于理解用户意图、判断是否需要澄清
        RunnableConfig config = AgentPromptContext.withPlanningPromptContext(
                RunnableConfig.builder().threadId(resolvedTaskId).build(),
                session,
                rawInstruction,
                extractContext(workspaceContext));

        // Planning Agent 的执行线程，用于生成具体任务卡片
        RunnableConfig planningConfig = AgentPromptContext.withPlanningPromptContext(
                RunnableConfig.builder().threadId(resolvedTaskId + "-planning").build(),
                session,
                rawInstruction,
                extractContext(workspaceContext));

        try {
            AssistantMessage supervisorResponse = supervisorAgent.call(prompt, config);
            String responseText = supervisorResponse.getText();

            if (needsClarification(responseText, session)) {
                List<String> questions = buildClarificationQuestions(responseText, rawInstruction);
                if (!questions.isEmpty()) {
                    return handleAskUser(session, questions);
                }
            }

            return runPlanning(session, prompt, planningConfig, resolvedTaskId);
        } catch (GraphRunnerException e) {
            log.error("Supervisor error for task {}: {}", resolvedTaskId, e.getMessage(), e);
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
        } else {
            session.setClarificationAnswers(List.of(feedback));
        }
        session.setTransitionReason("Resume: " + feedback);

        // 保持版本号每次请求只加1
        session.setVersion(session.getVersion() - 1);
        sessionService.save(session);
        sessionService.publishEvent(taskId, "RESUMED");

        RunnableConfig config = AgentPromptContext.withPlanningPromptContext(
                RunnableConfig.builder().threadId(taskId).build(),
                session,
                feedback,
                "");
        RunnableConfig planningConfig = AgentPromptContext.withPlanningPromptContext(
                RunnableConfig.builder().threadId(taskId + "-planning").build(),
                session,
                feedback,
                "");

        try {
            AssistantMessage supervisorResponse = supervisorAgent.call(feedback, config);
            if (needsClarification(supervisorResponse.getText(), session)) {
                List<String> questions = buildClarificationQuestions(supervisorResponse.getText(), feedback);
                if (!questions.isEmpty()) {
                    return handleAskUser(session, questions);
                }
            }
            return runPlanning(session, feedback, planningConfig, taskId);
        } catch (Exception e) {
            return failSession(session, e.getMessage());
        }
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

    private PlanTaskSession runPlanning(PlanTaskSession session, String prompt, RunnableConfig config, String taskId) {
        try {
            Optional<com.alibaba.cloud.ai.graph.OverAllState> state = planningAgent.invoke(prompt, config);
            List<UserPlanCard> planCards = extractPlanCardsFromState(state, taskId)
                    .orElseGet(() -> {
                        try {
                            return fallbackPlanCardsByText(prompt, config, taskId);
                        } catch (GraphRunnerException e) {
                            throw new SupervisorException(e.getMessage());
                        }
                    });
            qualityService.applyPlanReady(session, planCards);
            sessionService.save(session);
            sessionService.publishEvent(taskId, "PLAN_READY");
            return session;
        } catch (Exception e) {
            return failSession(session, e.getMessage());
        }
    }

    private Optional<List<UserPlanCard>> extractPlanCardsFromState(
            Optional<com.alibaba.cloud.ai.graph.OverAllState> state,
            String taskId) {
        if (state.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> data = state.get().data();
        Object structured = data.get("messages");
        if (structured == null) {
            structured = data.get("message");
        }
        if (structured instanceof PlanCardsOutput output && output.getPlanCards() != null) {
            return Optional.of(output.getPlanCards().stream()
                    .map(card -> {
                        card.setTaskId(taskId);
                        return card;
                    })
                    .toList());
        }
        if (structured instanceof String jsonText) {
            return Optional.of(qualityService.extractPlanCards(jsonText, taskId));
        }
        return Optional.empty();
    }

    private List<UserPlanCard> fallbackPlanCardsByText(String prompt, RunnableConfig config, String taskId) throws GraphRunnerException {
        AssistantMessage plannerResponse = planningAgent.call(prompt, config);
        return qualityService.extractPlanCards(plannerResponse.getText(), taskId);
    }

    private PlanTaskSession handleAskUser(PlanTaskSession session, List<String> questions) {
        session.setClarificationQuestions(questions);
        session.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        session.setTransitionReason("Information insufficient");
        sessionService.save(session);

        RequireInput requireInput = buildRequireInput(questions);
        sessionService.publishEvent(session.getTaskId(), "ASK_USER", requireInput);
        return session;
    }

    private PlanTaskSession failSession(PlanTaskSession session, String reason) {
        session.setPlanningPhase(PlanningPhaseEnum.FAILED);
        session.setTransitionReason(reason);
        sessionService.save(session);
        sessionService.publishEvent(session.getTaskId(), "FAILED");
        return session;
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
                || lower.contains("请问") || lower.contains("需要明确") || lower.contains("clarif");
    }

    private String buildPrompt(String rawInstruction, WorkspaceContext workspaceContext, String userFeedback, PlanTaskSession session) {
        StringBuilder sb = new StringBuilder("用户指令：").append(rawInstruction);
        if (workspaceContext != null) {
            if (workspaceContext.getSelectedMessages() != null && !workspaceContext.getSelectedMessages().isEmpty()) {
                sb.append("\n\n精选消息（优先参考）：\n").append(String.join("\n", workspaceContext.getSelectedMessages()));
            } else if (workspaceContext.getTimeRange() != null && !workspaceContext.getTimeRange().isBlank()) {
                sb.append("\n\n时间范围：").append(workspaceContext.getTimeRange());
            }
        }
        if (userFeedback != null && !userFeedback.isBlank()) {
            sb.append("\n\n用户补充回答：").append(userFeedback);
        }
        if (session.getClarificationQuestions() != null && !session.getClarificationQuestions().isEmpty()) {
            sb.append("\n\n之前的澄清问题：").append(String.join("；", session.getClarificationQuestions()));
        }
        return sb.toString();
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
        boolean hasDoc = containsAny(normalized,
                "文档", "纪要", "报告", "总结", "周报", "方案", "需求文档", "prd", "doc", "markdown", "md");
        boolean hasPpt = containsAny(normalized,
                "ppt", "幻灯", "slides", "slide", "演示稿", "路演", "deck");
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
        boolean hasOutputWords = containsAny(normalized, "输出", "形式", "目标", "文档", "ppt", "演示稿", "幻灯");
        boolean hasChoiceWords = containsAny(normalized, "还是", "两者", "都要", "二者", "或者");
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
        } catch (Exception ignored) {}
        return List.of();
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
