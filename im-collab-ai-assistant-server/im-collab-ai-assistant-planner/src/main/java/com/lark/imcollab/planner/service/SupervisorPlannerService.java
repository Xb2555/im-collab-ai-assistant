package com.lark.imcollab.planner.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
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
        String resolvedTaskId = taskId != null ? taskId : UUID.randomUUID().toString();
        PlanTaskSession session = sessionService.getOrCreate(resolvedTaskId);
        session.setTurnCount(session.getTurnCount() + 1);

        String prompt = buildPrompt(rawInstruction, workspaceContext, userFeedback, session);
        RunnableConfig config = RunnableConfig.builder().threadId(resolvedTaskId).build();

        try {
            AssistantMessage supervisorResponse = supervisorAgent.call(prompt, config);
            String responseText = supervisorResponse.getText();

            if (needsClarification(responseText, session)) {
                return handleAskUser(session, responseText);
            }

            return runPlanning(session, prompt, resolvedTaskId);
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
        sessionService.save(session);
        sessionService.publishEvent(taskId, "RESUMED");

        RunnableConfig config = RunnableConfig.builder().threadId(taskId).build();

        try {
            AssistantMessage supervisorResponse = supervisorAgent.call(feedback, config);
            if (needsClarification(supervisorResponse.getText(), session)) {
                return handleAskUser(session, supervisorResponse.getText());
            }
            return runPlanning(session, feedback, taskId);
        } catch (Exception e) {
            return failSession(session, e.getMessage());
        }
    }

    private PlanTaskSession runPlanning(PlanTaskSession session, String prompt, String taskId) {
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(taskId + "-planning").build();
            AssistantMessage plannerResponse = planningAgent.call(prompt, config);
            String plannerOutput = plannerResponse.getText();

            List<UserPlanCard> planCards = qualityService.extractPlanCards(plannerOutput, taskId);
            qualityService.applyPlanReady(session, planCards);
            sessionService.save(session);
            sessionService.publishEvent(taskId, "PLAN_READY");
            return session;
        } catch (Exception e) {
            return failSession(session, e.getMessage());
        }
    }

    private PlanTaskSession handleAskUser(PlanTaskSession session, String responseText) {
        List<String> questions = extractQuestions(responseText);
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
