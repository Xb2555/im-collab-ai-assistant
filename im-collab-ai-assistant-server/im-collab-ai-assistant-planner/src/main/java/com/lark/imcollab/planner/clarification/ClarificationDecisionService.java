package com.lark.imcollab.planner.clarification;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class ClarificationDecisionService {

    private static final String SAFE_QUESTION = "你希望我基于哪些内容，输出文档、PPT 还是摘要？";

    private final ReactAgent clarificationAgent;
    private final ObjectMapper objectMapper;
    private final PlannerProperties plannerProperties;
    private final PlannerConversationMemoryService memoryService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public ClarificationDecisionService(
            @Qualifier("clarificationAgent") ReactAgent clarificationAgent,
            ObjectMapper objectMapper,
            PlannerProperties plannerProperties,
            PlannerConversationMemoryService memoryService
    ) {
        this.clarificationAgent = clarificationAgent;
        this.objectMapper = objectMapper;
        this.plannerProperties = plannerProperties;
        this.memoryService = memoryService;
    }

    public ClarificationDecision decide(
            PlanTaskSession session,
            String rawInstruction,
            WorkspaceContext workspaceContext
    ) {
        if (clarificationAgent == null || !plannerProperties.getClarification().isModelEnabled()) {
            return ready(rawInstruction, "clarification model disabled");
        }
        String prompt = buildPrompt(session, rawInstruction, workspaceContext);
        RunnableConfig config = RunnableConfig.builder()
                .threadId((session == null ? "unknown" : session.getTaskId()) + "-clarification")
                .build();
        try {
            Optional<ClarificationDecision> decision = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return clarificationAgent.call(prompt, config).getText();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }, executorService)
                    .orTimeout(timeoutSeconds(), TimeUnit.SECONDS)
                    .thenApply(this::parse)
                    .get(timeoutSeconds() + 1L, TimeUnit.SECONDS);
            return decision
                    .map(this::guard)
                    .orElseGet(() -> askSafely("clarification model returned empty output"));
        } catch (Exception ignored) {
            return askSafely("clarification model unavailable");
        }
    }

    Optional<ClarificationDecision> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(text));
            ClarificationAction action = ClarificationAction.valueOf(root.path("action").asText("ASK_USER"));
            List<String> questions = strings(root.path("questions"));
            return Optional.of(ClarificationDecision.builder()
                    .action(action)
                    .questions(questions)
                    .intentSummary(root.path("intentSummary").asText(root.path("intent_summary").asText("")))
                    .confidence(root.path("confidence").asDouble(0.0d))
                    .reason(root.path("reason").asText("llm clarification decision"))
                    .build());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private ClarificationDecision guard(ClarificationDecision decision) {
        if (decision == null || decision.action() == null) {
            return askSafely("missing clarification action");
        }
        if (decision.confidence() < plannerProperties.getClarification().getPassThreshold()) {
            return askSafely("clarification confidence below threshold: " + decision.reason());
        }
        if (decision.action() == ClarificationAction.ASK_USER) {
            List<String> questions = limitQuestions(decision.questions());
            if (questions.isEmpty()) {
                return askSafely("clarification question is required");
            }
            return ClarificationDecision.builder()
                    .action(ClarificationAction.ASK_USER)
                    .questions(questions)
                    .intentSummary(decision.intentSummary())
                    .confidence(decision.confidence())
                    .reason(decision.reason())
                    .build();
        }
        if (decision.intentSummary() == null || decision.intentSummary().isBlank()) {
            return askSafely("ready decision missing intent summary");
        }
        return decision;
    }

    private String buildPrompt(PlanTaskSession session, String rawInstruction, WorkspaceContext workspaceContext) {
        StringBuilder builder = new StringBuilder();
        builder.append("Decide whether the planner needs to ask the user a clarification question before planning.\n");
        builder.append("Return JSON only. Do not create a plan. Do not execute anything.\n");
        builder.append("Supported planner outputs are only DOC, PPT, SUMMARY. Mermaid is only a DOC content requirement.\n");
        builder.append("If the request asks for unsupported output, ask one natural question about converting it to DOC, PPT, or SUMMARY.\n");
        builder.append("JSON shape: {\"action\":\"ASK_USER|READY\",\"questions\":[],\"intentSummary\":\"\",\"confidence\":0.0,\"reason\":\"\"}\n");
        builder.append("Ask only when missing information blocks planning or execution boundaries. Prefer one concise question.\n\n");
        builder.append("Session phase: ").append(session == null ? "INTAKE" : session.getPlanningPhase()).append("\n");
        builder.append("User instruction: ").append(rawInstruction == null ? "" : rawInstruction.trim()).append("\n");
        builder.append("Workspace context: ").append(contextSummary(workspaceContext)).append("\n");
        builder.append("Previous questions: ").append(session == null || session.getClarificationQuestions() == null
                ? "[]" : session.getClarificationQuestions()).append("\n");
        builder.append("Previous answers: ").append(session == null || session.getClarificationAnswers() == null
                ? "[]" : session.getClarificationAnswers()).append("\n");
        String memoryContext = memoryService == null ? "" : memoryService.renderContext(session);
        if (memoryContext != null && !memoryContext.isBlank()) {
            builder.append("\nConversation memory:\n").append(memoryContext).append("\n");
        }
        return builder.toString();
    }

    private String contextSummary(WorkspaceContext context) {
        if (context == null) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        if (context.getSelectedMessages() != null && !context.getSelectedMessages().isEmpty()) {
            parts.add("selectedMessages=" + Math.min(context.getSelectedMessages().size(), 20));
        }
        if (context.getSelectedMessageIds() != null && !context.getSelectedMessageIds().isEmpty()) {
            parts.add("selectedMessageIds=" + context.getSelectedMessageIds().size());
        }
        if (context.getDocRefs() != null && !context.getDocRefs().isEmpty()) {
            parts.add("docRefs=" + context.getDocRefs().size());
        }
        if (context.getAttachmentRefs() != null && !context.getAttachmentRefs().isEmpty()) {
            parts.add("attachments=" + context.getAttachmentRefs().size());
        }
        if (context.getTimeRange() != null && !context.getTimeRange().isBlank()) {
            parts.add("timeRange=" + context.getTimeRange());
        }
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            String text = value.asText();
            if (text != null && !text.isBlank()) {
                values.add(text.trim());
            }
        });
        return values;
    }

    private List<String> limitQuestions(List<String> questions) {
        if (questions == null) {
            return List.of();
        }
        return questions.stream()
                .filter(question -> question != null && !question.isBlank())
                .map(this::normalizeQuestion)
                .distinct()
                .limit(3)
                .toList();
    }

    private String normalizeQuestion(String question) {
        String normalized = question == null ? "" : question.trim();
        while (normalized.startsWith("我还需要确认一下：") || normalized.startsWith("我还需要确认一下:")) {
            normalized = normalized.substring("我还需要确认一下：".length()).trim();
        }
        return normalized;
    }

    private ClarificationDecision ready(String intentSummary, String reason) {
        return ClarificationDecision.builder()
                .action(ClarificationAction.READY)
                .questions(List.of())
                .intentSummary(intentSummary == null ? "" : intentSummary.trim())
                .confidence(1.0d)
                .reason(reason)
                .build();
    }

    private ClarificationDecision askSafely(String reason) {
        return ClarificationDecision.builder()
                .action(ClarificationAction.ASK_USER)
                .questions(List.of(SAFE_QUESTION))
                .intentSummary("")
                .confidence(0.0d)
                .reason(reason)
                .build();
    }

    private long timeoutSeconds() {
        return Math.max(1, plannerProperties.getClarification().getTimeoutSeconds());
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
}
