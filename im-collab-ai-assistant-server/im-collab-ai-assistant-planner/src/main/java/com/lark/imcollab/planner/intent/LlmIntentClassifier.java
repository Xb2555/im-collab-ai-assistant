package com.lark.imcollab.planner.intent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class LlmIntentClassifier {

    private final ReactAgent intentAgent;
    private final ObjectMapper objectMapper;
    private final PlannerProperties plannerProperties;
    private final PlannerConversationMemoryService memoryService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public LlmIntentClassifier(
            @Qualifier("intentClassifierAgent") ReactAgent intentAgent,
            ObjectMapper objectMapper,
            PlannerProperties plannerProperties,
            PlannerConversationMemoryService memoryService
    ) {
        this.intentAgent = intentAgent;
        this.objectMapper = objectMapper;
        this.plannerProperties = plannerProperties;
        this.memoryService = memoryService;
    }

    public Optional<IntentRoutingResult> classify(PlanTaskSession session, String rawInput, boolean existingSession) {
        if (intentAgent == null || !plannerProperties.getIntent().isModelEnabled()) {
            return Optional.empty();
        }
        String prompt = buildPrompt(session, rawInput, existingSession);
        RunnableConfig config = RunnableConfig.builder()
                .threadId((session == null ? "unknown" : session.getTaskId()) + "-intent")
                .build();
        try {
            return CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return intentAgent.call(prompt, config).getText();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }, executorService)
                    .orTimeout(timeoutSeconds(), TimeUnit.SECONDS)
                    .thenApply(this::parse)
                    .get(timeoutSeconds() + 1L, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    Optional<IntentRoutingResult> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(text));
            TaskCommandTypeEnum type = TaskCommandTypeEnum.valueOf(root.path("intent").asText("UNKNOWN"));
            String normalizedInput = root.path("normalizedInput").asText("");
            if (normalizedInput.isBlank()) {
                normalizedInput = root.path("normalized_input").asText("");
            }
            return Optional.of(new IntentRoutingResult(
                    type,
                    root.path("confidence").asDouble(0.0d),
                    root.path("reason").asText("llm intent classification"),
                    normalizedInput,
                    root.path("needsClarification").asBoolean(false)
                            || root.path("needs_clarification").asBoolean(false),
                    normalizeReadOnlyView(firstNonBlank(
                            root.path("readOnlyView").asText(null),
                            root.path("read_only_view").asText(null),
                            root.path("queryView").asText(null),
                            root.path("query_view").asText(null)
                    ))
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String buildPrompt(PlanTaskSession session, String rawInput, boolean existingSession) {
        StringBuilder builder = new StringBuilder();
        builder.append("You classify one user message into a fixed task command intent.\n");
        builder.append("Return JSON only. Do not plan steps, do not execute actions, do not answer the user.\n");
        builder.append("Allowed intents: START_TASK, ANSWER_CLARIFICATION, ADJUST_PLAN, QUERY_STATUS, CONFIRM_ACTION, CANCEL_TASK, UNKNOWN.\n");
        builder.append("JSON shape: {\"intent\":\"...\",\"confidence\":0.0,\"reason\":\"\",\"normalizedInput\":\"\",\"needsClarification\":false,\"readOnlyView\":\"PLAN|STATUS|ARTIFACTS|\"}\n");
        builder.append("Decision hints:\n");
        builder.append("- QUERY_STATUS means the user asks progress, status, task overview, current plan summary, full plan, existing artifacts, or what is being done.\n");
        builder.append("- For QUERY_STATUS, set readOnlyView=PLAN when the user wants the stored plan/steps; STATUS when they want progress/current status; ARTIFACTS when they want outputs/links/artifacts.\n");
        builder.append("- ADJUST_PLAN means the user asks to add, remove, update, reorder, or regenerate plan steps.\n");
        builder.append("- CONFIRM_ACTION requires an explicit execution/retry request, such as 开始执行 / 确认执行 / 没问题，执行 / 重试一下. Generic approval like 这个方案还行 or 就这样 is not enough.\n");
        builder.append("- ANSWER_CLARIFICATION means the system is waiting for user details and the user provides those details.\n");
        builder.append("- In ASK_USER phase, choose ANSWER_CLARIFICATION only when the latest message directly answers the pending question or supplies the missing material. Meta questions, identity/capability questions, greetings, or a standalone new task request are not clarification answers.\n");
        builder.append("- START_TASK requires a real task request with a work goal or deliverable. Casual chat, greetings, mood sharing, jokes, and meta questions are UNKNOWN.\n");
        builder.append("- A request to summarize, organize, write, generate, extract, or convert workspace/chat/document context into a document, PPT, summary, report, outline, risk list, or follow-up note is START_TASK.\n");
        builder.append("- If the user asks to pull recent/prior/group/chat messages and turn them into an output, classify as START_TASK. Context acquisition details like time range, topics, exclusions, or audience do not make it UNKNOWN.\n");
        builder.append("- Even when existingSession=true and hasPlan=true, choose START_TASK for a standalone new work request with a concrete deliverable; choose ADJUST_PLAN only when the user explicitly modifies the current plan.\n");
        builder.append("- If the user explicitly asks to 新建一个任务 / 新建任务 / 新开一个任务 / 另起一个任务 / 再开一个任务 and also gives a deliverable or work goal, choose START_TASK. This creates an isolated task, not an adjustment to the bound conversation task.\n");
        builder.append("- If phase=COMPLETED, a new concrete work request should usually be START_TASK. Do not treat it as ADJUST_PLAN unless the user explicitly says they want to revise/change the just-completed plan or artifact.\n");
        builder.append("- If an explicit fresh-task phrase appears inside a title, topic, slide text, or quoted content rather than as the user's command, classify by the actual command intent.\n");
        builder.append("- Do not classify a concrete deliverable request as UNKNOWN just because another task is already active in the chat.\n");
        builder.append("- The user message may begin with a Feishu mention placeholder like @_user_1. Ignore that prefix and classify the remaining sentence.\n");
        builder.append("- Identity or capability questions like 你是谁 / 你能做什么 are UNKNOWN unless the user also asks for a concrete deliverable.\n");
        builder.append("- UNKNOWN means the message cannot be safely mapped to one fixed intent, including small talk or non-task chat.\n\n");
        builder.append("Session:\n");
        builder.append("- existingSession: ").append(existingSession).append("\n");
        builder.append("- phase: ").append(session == null || session.getPlanningPhase() == null
                ? PlanningPhaseEnum.INTAKE
                : session.getPlanningPhase()).append("\n");
        builder.append("- hasPlan: ").append(hasPlan(session)).append("\n");
        builder.append("- recentCards: ").append(cardSummary(session)).append("\n");
        String memoryContext = memoryService == null ? "" : memoryService.renderContext(session);
        if (memoryContext != null && !memoryContext.isBlank()) {
            builder.append("Conversation memory:\n").append(memoryContext).append("\n");
        }
        builder.append("User message: ").append(rawInput == null ? "" : rawInput.trim());
        return builder.toString();
    }

    private boolean hasPlan(PlanTaskSession session) {
        return session != null
                && ((session.getPlanCards() != null && !session.getPlanCards().isEmpty())
                || session.getPlanBlueprint() != null);
    }

    private String cardSummary(PlanTaskSession session) {
        if (session == null || session.getPlanCards() == null || session.getPlanCards().isEmpty()) {
            return "[]";
        }
        List<UserPlanCard> cards = session.getPlanCards();
        StringBuilder builder = new StringBuilder("[");
        int limit = Math.min(cards.size(), 3);
        for (int index = 0; index < limit; index++) {
            UserPlanCard card = cards.get(index);
            if (index > 0) {
                builder.append("; ");
            }
            builder.append(card.getCardId()).append("|")
                    .append(card.getType()).append("|")
                    .append(card.getTitle());
        }
        if (cards.size() > limit) {
            builder.append("; +").append(cards.size() - limit).append(" more");
        }
        builder.append("]");
        return builder.toString();
    }

    private String extractJson(String text) {
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private String normalizeReadOnlyView(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PLAN", "STATUS", "ARTIFACTS" -> normalized;
            default -> null;
        };
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private long timeoutSeconds() {
        return Math.max(1, plannerProperties.getIntent().getTimeoutSeconds());
    }
}
