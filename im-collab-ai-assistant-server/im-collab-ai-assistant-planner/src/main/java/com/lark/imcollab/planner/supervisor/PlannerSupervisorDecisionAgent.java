package com.lark.imcollab.planner.supervisor;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class PlannerSupervisorDecisionAgent {

    private final ReactAgent supervisorAgent;
    private final ObjectMapper objectMapper;
    private final PlannerProperties plannerProperties;
    private final PlannerConversationMemoryService memoryService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public PlannerSupervisorDecisionAgent(
            @Qualifier("supervisorAgent") ReactAgent supervisorAgent,
            ObjectMapper objectMapper,
            PlannerProperties plannerProperties,
            PlannerConversationMemoryService memoryService
    ) {
        this.supervisorAgent = supervisorAgent;
        this.objectMapper = objectMapper;
        this.plannerProperties = plannerProperties;
        this.memoryService = memoryService;
    }

    public PlannerSupervisorDecisionResult decide(
            PlanTaskSession session,
            PlannerSupervisorDecision intakeDecision,
            String rawInstruction
    ) {
        PlannerSupervisorAction guarded = guardedAction(session, intakeDecision);
        if (guarded != null) {
            return PlannerSupervisorDecisionResult.of(guarded, 1.0d, intakeDecision == null ? "guarded route" : intakeDecision.reason());
        }
        if (supervisorAgent == null) {
            return safeUnknown("supervisor agent unavailable");
        }
        String taskId = session == null ? "unknown" : session.getTaskId();
        RunnableConfig config = RunnableConfig.builder()
                .threadId(taskId + ":planner-supervisor-decision")
                .build();
        try {
            Optional<PlannerSupervisorDecisionResult> result = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return supervisorAgent.call(buildPrompt(session, intakeDecision, rawInstruction), config).getText();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }, executorService)
                    .orTimeout(timeoutSeconds(), TimeUnit.SECONDS)
                    .thenApply(this::parse)
                    .get(timeoutSeconds() + 1L, TimeUnit.SECONDS);
            return result.map(this::guard).orElseGet(() -> safeUnknown("supervisor decision returned empty output"));
        } catch (Exception ignored) {
            return safeUnknown("supervisor decision unavailable");
        }
    }

    private PlannerSupervisorAction guardedAction(PlanTaskSession session, PlannerSupervisorDecision decision) {
        PlannerSupervisorAction action = decision == null ? PlannerSupervisorAction.UNKNOWN : decision.action();
        if (action == PlannerSupervisorAction.CANCEL_TASK
                || action == PlannerSupervisorAction.CONFIRM_ACTION
                || action == PlannerSupervisorAction.QUERY_STATUS) {
            return action;
        }
        if (action == PlannerSupervisorAction.PLAN_ADJUSTMENT && hasUsablePlan(session)) {
            return PlannerSupervisorAction.PLAN_ADJUSTMENT;
        }
        if (session != null
                && session.getPlanningPhase() == PlanningPhaseEnum.ASK_USER
                && action != PlannerSupervisorAction.CANCEL_TASK
                && action != PlannerSupervisorAction.QUERY_STATUS) {
            return PlannerSupervisorAction.CLARIFICATION_REPLY;
        }
        if (session != null
                && session.getPlanningPhase() == PlanningPhaseEnum.FAILED
                && !hasUsablePlan(session)
                && action != PlannerSupervisorAction.CANCEL_TASK
                && action != PlannerSupervisorAction.QUERY_STATUS
                && action != PlannerSupervisorAction.CONFIRM_ACTION) {
            return PlannerSupervisorAction.NEW_TASK;
        }
        if (action == PlannerSupervisorAction.NEW_TASK && (session == null || !hasUsablePlan(session))) {
            return PlannerSupervisorAction.NEW_TASK;
        }
        return null;
    }

    private boolean hasUsablePlan(PlanTaskSession session) {
        if (session == null) {
            return false;
        }
        if (session.getPlanCards() != null && !session.getPlanCards().isEmpty()) {
            return true;
        }
        return session.getPlanBlueprint() != null
                && session.getPlanBlueprint().getPlanCards() != null
                && !session.getPlanBlueprint().getPlanCards().isEmpty();
    }

    private String buildPrompt(PlanTaskSession session, PlannerSupervisorDecision intakeDecision, String rawInstruction) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are the Planner supervisor. Choose the next action only.\n");
        builder.append("Return JSON only: {\"action\":\"NEW_TASK|CLARIFICATION_REPLY|PLAN_ADJUSTMENT|QUERY_STATUS|CONFIRM_ACTION|CANCEL_TASK|UNKNOWN\",")
                .append("\"confidence\":0.0,\"reason\":\"\",\"needsClarification\":false,\"clarificationQuestion\":\"\",\"userFacingReply\":\"\"}.\n");
        builder.append("Do not create a plan. Do not execute. Do not invent tools. Supported outputs: DOC, PPT, SUMMARY.\n");
        builder.append("Full plan/status queries must be QUERY_STATUS. Neutral acknowledgements such as '这个方案还行' should be UNKNOWN with a friendly userFacingReply.\n");
        builder.append("Add/remove/change/reorder requests should be PLAN_ADJUSTMENT. Only explicit start/execute/confirm should be CONFIRM_ACTION.\n\n");
        builder.append("If the user asks for any additional/final/new deliverable to be included in the current plan, choose PLAN_ADJUSTMENT even when the wording is casual.\n");
        builder.append("Never use QUERY_STATUS or UNKNOWN to say that a plan was updated. Only the replan node may update the plan after patch merge.\n\n");
        builder.append("For UNKNOWN, fill userFacingReply with one natural Chinese sentence. Do not use stiff phrases like '我没完全判断清楚'.\n");
        builder.append("If the user is just giving feedback, userFacingReply should acknowledge the feedback and say the current plan is kept.\n\n");
        builder.append("Session phase: ").append(session == null ? "NONE" : session.getPlanningPhase()).append("\n");
        builder.append("Has plan: ").append(session != null && session.getPlanBlueprint() != null).append("\n");
        builder.append("Intake hint: ").append(intakeDecision == null ? "" : intakeDecision.action()).append(" / ")
                .append(intakeDecision == null ? "" : intakeDecision.reason()).append("\n");
        builder.append("User input: ").append(rawInstruction == null ? "" : rawInstruction.trim()).append("\n");
        if (session != null && session.getPlanCards() != null && !session.getPlanCards().isEmpty()) {
            builder.append("Current plan cards:\n");
            session.getPlanCards().stream().limit(8).forEach(card -> builder
                    .append("- ").append(card.getCardId()).append(" | ")
                    .append(card.getType()).append(" | ")
                    .append(card.getTitle()).append("\n"));
        }
        String memory = memoryService == null ? "" : memoryService.renderContext(session);
        if (memory != null && !memory.isBlank()) {
            builder.append("Conversation memory:\n").append(memory).append("\n");
        }
        return builder.toString();
    }

    private Optional<PlannerSupervisorDecisionResult> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(text));
            PlannerSupervisorAction action = PlannerSupervisorAction.valueOf(root.path("action").asText("UNKNOWN"));
            return Optional.of(PlannerSupervisorDecisionResult.builder()
                    .action(action)
                    .confidence(root.path("confidence").asDouble(0.0d))
                    .reason(root.path("reason").asText(""))
                    .needsClarification(root.path("needsClarification").asBoolean(false))
                    .clarificationQuestion(root.path("clarificationQuestion").asText(""))
                    .userFacingReply(root.path("userFacingReply").asText(""))
                    .build());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private PlannerSupervisorDecisionResult guard(PlannerSupervisorDecisionResult result) {
        if (result == null || result.action() == null) {
            return safeUnknown("missing action");
        }
        if (result.confidence() < plannerProperties.getGraph().getSupervisorDecisionPassThreshold()) {
            return PlannerSupervisorDecisionResult.builder()
                    .action(PlannerSupervisorAction.UNKNOWN)
                    .confidence(result.confidence())
                    .reason("low confidence: " + result.reason())
                    .needsClarification(true)
                    .clarificationQuestion(firstNonBlank(
                            result.clarificationQuestion(),
                            "我先不动当前计划。你想看细节、调整步骤，还是推进执行？"))
                    .userFacingReply(result.userFacingReply())
                    .build();
        }
        return result;
    }

    private PlannerSupervisorDecisionResult safeUnknown(String reason) {
        return PlannerSupervisorDecisionResult.builder()
                .action(PlannerSupervisorAction.UNKNOWN)
                .confidence(0.0d)
                .reason(reason)
                .needsClarification(true)
                .clarificationQuestion("我先不动当前计划。你想看细节、调整步骤，还是推进执行？")
                .build();
    }

    private String extractJson(String text) {
        String trimmed = text == null ? "" : text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private long timeoutSeconds() {
        return Math.max(1, plannerProperties.getGraph().getSupervisorDecisionTimeoutSeconds());
    }
}
