package com.lark.imcollab.planner.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class SupervisorPlannerService {

    private final ReactAgent supervisorAgent;
    private final SequentialAgent planningSequence;
    private final PlannerSessionService sessionService;
    private final PlanQualityService qualityService;
    private final PlannerProperties plannerProperties;

    public SupervisorPlannerService(
            @Qualifier("supervisorAgent") ReactAgent supervisorAgent,
            @Qualifier("planningSequence") SequentialAgent planningSequence,
            PlannerSessionService sessionService,
            PlanQualityService qualityService,
            PlannerProperties plannerProperties) {
        this.supervisorAgent = supervisorAgent;
        this.planningSequence = planningSequence;
        this.sessionService = sessionService;
        this.qualityService = qualityService;
        this.plannerProperties = plannerProperties;
    }

    public PlanTaskSession plan(String rawInstruction, String context, String taskId, String userFeedback) {
        String resolvedTaskId = taskId != null ? taskId : UUID.randomUUID().toString();
        PlanTaskSession session = sessionService.getOrCreate(resolvedTaskId);
        session.setTurnCount(session.getTurnCount() + 1);

        String prompt = buildPrompt(rawInstruction, context, userFeedback, session);
        RunnableConfig config = RunnableConfig.builder().threadId(resolvedTaskId).build();

        try {
            AssistantMessage supervisorResponse = supervisorAgent.call(prompt, config);
            String responseText = supervisorResponse.getText();

            if (needsClarification(responseText, session)) {
                return handleAskUser(session, responseText);
            }

            return runPlanningSequence(session, prompt, config);
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
        sessionService.publishEvent(taskId, "INTERRUPTED", "User interrupt");
        return session;
    }

    public PlanTaskSession resume(String taskId, String feedback, boolean replanFromRoot) {
        PlanTaskSession session = sessionService.get(taskId);
        session.setAborted(false);
        if (replanFromRoot) {
            session.setClarificationAnswers(null);
            session.setClarificationQuestions(null);
            session.setPlanScore(0);
        } else {
            session.setClarificationAnswers(List.of(feedback));
        }
        session.setTransitionReason("Resume: " + feedback);
        sessionService.save(session);
        sessionService.publishEvent(taskId, "RESUMED", feedback);

        RunnableConfig config = RunnableConfig.builder().threadId(taskId).build();

        try {
            AssistantMessage supervisorResponse = supervisorAgent.call(feedback, config);
            if (needsClarification(supervisorResponse.getText(), session)) {
                return handleAskUser(session, supervisorResponse.getText());
            }
            return runPlanningSequence(session, feedback, config);
        } catch (Exception e) {
            return failSession(session, e.getMessage());
        }
    }

    private PlanTaskSession runPlanningSequence(PlanTaskSession session, String prompt, RunnableConfig config) {
        String taskId = session.getTaskId();
        int threshold = plannerProperties.getQuality().getPassThreshold();
        int maxReplan = plannerProperties.getQuality().getMaxReplanAttempts();

        try {
            String firstOutput = invokeSequence(prompt, config);
            System.out.println("=== Planning Output ===");
            System.out.println(firstOutput);
            System.out.println("=====================");
            List<UserPlanCard> planCards = qualityService.extractPlanCards(firstOutput, taskId);
            int score = qualityService.extractScore(firstOutput);

            if (score >= threshold || session.getTurnCount() > maxReplan + 1) {
                qualityService.applyPlanReady(session, planCards, Math.max(score, 0));
                sessionService.save(session);
                sessionService.publishEvent(taskId, "PLAN_READY", "Score: " + score);
                return session;
            }

            session.setPlanScore(score);
            sessionService.publishEvent(taskId, "REPLAN", "Score: " + score);

            List<String> suggestions = qualityService.extractSuggestions(firstOutput);
            String replanPrompt = prompt + "\n\n改进建议：" + String.join("；", suggestions);

            String improvedOutput = invokeSequence(replanPrompt, config);
            List<UserPlanCard> improvedCards = qualityService.extractPlanCards(improvedOutput, taskId);
            int improvedScore = qualityService.extractScore(improvedOutput);

            qualityService.applyPlanReady(session, improvedCards, improvedScore);
            sessionService.save(session);
            sessionService.publishEvent(taskId, "PLAN_READY", "Improved score: " + improvedScore);
            return session;

        } catch (Exception e) {
            return failSession(session, e.getMessage());
        }
    }

    private String invokeSequence(String prompt, RunnableConfig config) throws GraphRunnerException {
        Optional<OverAllState> result = planningSequence.invoke(prompt, config);
        return result.flatMap(state -> state.<List<?>>value("messages"))
                .map(msgs -> msgs.stream()
                        .filter(m -> m instanceof AssistantMessage)
                        .map(m -> ((AssistantMessage) m).getText())
                        .reduce("", (a, b) -> b))
                .orElse("");
    }

    private PlanTaskSession handleAskUser(PlanTaskSession session, String responseText) {
        List<String> questions = extractQuestions(responseText);
        session.setClarificationQuestions(questions);
        session.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        session.setTransitionReason("Information insufficient");
        sessionService.save(session);
        sessionService.publishEvent(session.getTaskId(), "ASK_USER", String.join("|", questions));
        return session;
    }

    private PlanTaskSession failSession(PlanTaskSession session, String reason) {
        session.setPlanningPhase(PlanningPhaseEnum.FAILED);
        session.setTransitionReason(reason);
        sessionService.save(session);
        sessionService.publishEvent(session.getTaskId(), "FAILED", reason);
        return session;
    }

    private boolean needsClarification(String text, PlanTaskSession session) {
        if (session.getClarificationAnswers() != null && !session.getClarificationAnswers().isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("\"questions\"") || lower.contains("questions:")
                || lower.contains("请问") || lower.contains("需要明确") || lower.contains("clarif");
    }

    private String buildPrompt(String rawInstruction, String context, String userFeedback, PlanTaskSession session) {
        StringBuilder sb = new StringBuilder("用户指令：").append(rawInstruction);
        if (context != null && !context.isBlank()) {
            sb.append("\n\n上下文信息：").append(context);
        }
        if (userFeedback != null && !userFeedback.isBlank()) {
            sb.append("\n\n用户补充回答：").append(userFeedback);
        }
        if (session.getClarificationQuestions() != null && !session.getClarificationQuestions().isEmpty()) {
            sb.append("\n\n之前的澄清问题：").append(String.join("；", session.getClarificationQuestions()));
        }
        if (session.getPlanScore() > 0) {
            sb.append("\n\n上次规划评分：").append(session.getPlanScore()).append("，请改进。");
        }
        return sb.toString();
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
}
