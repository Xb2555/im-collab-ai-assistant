package com.lark.imcollab.planner.replan;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.ReplanScopeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class PlanAdjustmentInterpreter {

    private final ReactAgent replanAgent;
    private final ObjectMapper objectMapper;
    private final PlannerProperties plannerProperties;
    private final PlannerConversationMemoryService memoryService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public PlanAdjustmentInterpreter(
            @Qualifier("replanAgent") ReactAgent replanAgent,
            ObjectMapper objectMapper,
            PlannerProperties plannerProperties,
            PlannerConversationMemoryService memoryService
    ) {
        this.replanAgent = replanAgent;
        this.objectMapper = objectMapper;
        this.plannerProperties = plannerProperties;
        this.memoryService = memoryService;
    }

    public PlanPatchIntent interpret(PlanTaskSession session, String instruction, WorkspaceContext workspaceContext) {
        List<UserPlanCard> cards = activeCards(session);
        if (!hasText(instruction)) {
            return clarify("我没收到具体修改内容，你想新增、删除、修改还是调整哪一步？");
        }
        Optional<PlanPatchIntent> model = interpretWithModel(session, cards, instruction)
                .flatMap(intent -> normalizeModelIntent(intent, cards, instruction));
        if (model.isPresent()) {
            return model.get();
        }
        return clarify("我先不改当前计划。你可以告诉我想新增哪项、去掉哪项，或把哪一步改成什么。");
    }

    private Optional<PlanPatchIntent> interpretWithModel(PlanTaskSession session, List<UserPlanCard> cards, String instruction) {
        if (replanAgent == null) {
            return Optional.empty();
        }
        if (!plannerProperties.getReplan().isPatchIntentModelEnabled()) {
            return Optional.empty();
        }
        String prompt = buildPrompt(session, cards, instruction);
        String taskId = session == null || !hasText(session.getTaskId()) ? "unknown" : session.getTaskId();
        RunnableConfig config = RunnableConfig.builder()
                .threadId(taskId + ":planner:replan")
                .build();
        try {
            return CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return replanAgent.call(prompt, config).getText();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }, executorService)
                    .orTimeout(timeoutSeconds(), TimeUnit.SECONDS)
                    .thenApply(this::parseModelIntent)
                    .get(timeoutSeconds() + 1L, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<PlanPatchIntent> parseModelIntent(String text) {
        if (!hasText(text)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(text));
            PlanPatchOperation operation = parseOperation(root.path("operation").asText(null));
            if (operation == null) {
                return Optional.empty();
            }
            List<String> targets = strings(root.path("targetCardIds"));
            List<String> ordered = strings(root.path("orderedCardIds"));
            List<PlanPatchCardDraft> drafts = new ArrayList<>();
            root.path("newCardDrafts").forEach(node -> drafts.add(PlanPatchCardDraft.builder()
                    .title(node.path("title").asText(null))
                    .description(node.path("description").asText(null))
                    .type(parseType(node.path("type").asText(null)))
                    .build()));
            return Optional.of(PlanPatchIntent.builder()
                    .operation(operation)
                    .targetCardIds(targets)
                    .orderedCardIds(ordered)
                    .newCardDrafts(drafts)
                    .confidence(root.path("confidence").asDouble(0.0d))
                    .reason(root.path("reason").asText(null))
                    .clarificationQuestion(root.path("clarificationQuestion").asText(null))
                    .build());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<PlanPatchIntent> normalizeModelIntent(PlanPatchIntent intent, List<UserPlanCard> cards, String instruction) {
        if (intent == null || intent.getOperation() == null) {
            return Optional.empty();
        }
        if (!passesModelThreshold(intent)) {
            return Optional.of(clarify(hasText(intent.getClarificationQuestion())
                    ? intent.getClarificationQuestion()
                    : "我先不动当前计划。你可以再说清楚想改哪一步、改成什么吗？"));
        }
        if (intent.getOperation() == PlanPatchOperation.CLARIFY_REQUIRED) {
            return Optional.of(clarify(hasText(intent.getClarificationQuestion())
                    ? intent.getClarificationQuestion()
                    : "我先不动当前计划。你想新增、删除、改写，还是调整顺序？"));
        }
        if (intent.getOperation() == PlanPatchOperation.REGENERATE_ALL) {
            return Optional.of(intent);
        }
        if ((intent.getOperation() == PlanPatchOperation.REMOVE_STEP
                || intent.getOperation() == PlanPatchOperation.UPDATE_STEP
                || intent.getOperation() == PlanPatchOperation.MERGE_STEP)
                && (intent.getTargetCardIds() == null || intent.getTargetCardIds().isEmpty())) {
            return Optional.of(clarify("我先不动当前计划。你想改的是哪一个步骤？"));
        }
        if (intent.getOperation() == PlanPatchOperation.REMOVE_STEP
                || intent.getOperation() == PlanPatchOperation.UPDATE_STEP
                || intent.getOperation() == PlanPatchOperation.MERGE_STEP) {
            List<String> targets = validCardIds(cards, intent.getTargetCardIds());
            if (targets.isEmpty()) {
                return Optional.of(clarify("我先保留当前计划。你要改的那一步我没有对上，可以换个说法指出具体步骤吗？"));
            }
            intent.setTargetCardIds(targets);
        }
        if (intent.getOperation() == PlanPatchOperation.MERGE_STEP
                && (intent.getTargetCardIds() == null || intent.getTargetCardIds().size() < 2)) {
            return Optional.of(clarify("我先不合并步骤。你想把哪一步放进哪一个已有步骤里？"));
        }
        if (intent.getOperation() == PlanPatchOperation.ADD_STEP
                || intent.getOperation() == PlanPatchOperation.UPDATE_STEP
                || intent.getOperation() == PlanPatchOperation.MERGE_STEP) {
            List<PlanPatchCardDraft> drafts = validDrafts(intent.getNewCardDrafts());
            if (drafts.isEmpty()) {
                return Optional.of(clarify("我先不改当前计划。你想把这一步改成什么产物或内容？"));
            }
            intent.setNewCardDrafts(drafts);
        }
        if (intent.getOperation() == PlanPatchOperation.REORDER_STEP) {
            List<String> ordered = validFullOrder(cards, intent.getOrderedCardIds());
            if (ordered.isEmpty()) {
                return Optional.of(clarify("我先不改顺序。你可以按 1、2、3 说一下新的步骤顺序。"));
            }
            intent.setOrderedCardIds(ordered);
        }
        return Optional.of(intent);
    }

    private String buildPrompt(PlanTaskSession session, List<UserPlanCard> cards, String instruction) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are the replan sub-agent inside a Spring AI Alibaba StateGraph planner. ");
        builder.append("Follow a Claude-Code-like boundary: you decide the semantic patch, while Java tools validate and merge it. ");
        builder.append("Do not rely on hidden rules, do not rewrite untouched steps, and do not claim execution happened.\n");
        builder.append("Allowed operations: ADD_STEP, REMOVE_STEP, UPDATE_STEP, MERGE_STEP, REORDER_STEP, REGENERATE_ALL, CLARIFY_REQUIRED.\n");
        builder.append("Return JSON only: {\"operation\":\"...\",\"targetCardIds\":[],\"orderedCardIds\":[],\"newCardDrafts\":[{\"title\":\"\",\"description\":\"\",\"type\":\"DOC|PPT|SUMMARY\"}],\"confidence\":0.0,\"reason\":\"\",\"clarificationQuestion\":\"\"}\n");
        builder.append("Rules:\n");
        builder.append("- Use only these existing cardIds when targeting steps. Never invent targetCardIds.\n");
        builder.append("- ADD_STEP must include at least one complete newCardDraft and empty targetCardIds.\n");
        builder.append("- When the user asks for an extra/final/additional deliverable, prefer ADD_STEP. Do not rewrite or replace existing cards.\n");
        builder.append("- Infer the new draft type from the requested output form: document/table/report as DOC, slides/presentation as PPT, short message/summary/conclusion as SUMMARY.\n");
        builder.append("- REMOVE_STEP and UPDATE_STEP must target existing cardIds.\n");
        builder.append("- When the user says to change X into Y, replace wording, remove a phrase from a step, or says '把/将 X 改成 Y' / '不要提 Z', prefer UPDATE_STEP if one existing card semantically matches X. Include the rewritten card draft.\n");
        builder.append("- Use MERGE_STEP when the user wants one existing step not to be separate anymore, but included/folded/placed inside another existing step. targetCardIds must put the destination card first, then the source card(s) to remove. newCardDrafts must contain the rewritten destination card.\n");
        builder.append("- If the user mentions 'last step' or describes a unique existing card by meaning, target that existing card id. Do not ask clarification when the target is clear from current cards.\n");
        builder.append("- If the user says keep A and do not do B, remove only the matching B step and preserve the other steps.\n");
        builder.append("- REORDER_STEP must return orderedCardIds containing every current cardId exactly once.\n");
        builder.append("- REGENERATE_ALL only when the user explicitly asks to redo the whole plan.\n");
        builder.append("- If the request is ambiguous or unsupported, return CLARIFY_REQUIRED with one natural question.\n");
        builder.append("- Stable deliverables are only DOC, PPT, SUMMARY. Mermaid is a DOC content requirement, not a separate artifact.\n");
        appendScopeHints(builder, session, cards);
        builder.append("Current cards:\n");
        for (UserPlanCard card : cards) {
            builder.append("- ")
                    .append(card.getCardId()).append(" | ")
                    .append(card.getType()).append(" | ")
                    .append(card.getTitle()).append(" | ")
                    .append(card.getDescription() == null ? "" : card.getDescription())
                    .append("\n");
        }
        String memoryContext = memoryService == null ? "" : memoryService.renderContext(session);
        if (memoryContext != null && !memoryContext.isBlank()) {
            builder.append("Conversation memory:\n")
                    .append(memoryContext)
                    .append("\n");
        }
        builder.append("User adjustment: ").append(instruction == null ? "" : instruction.trim());
        return builder.toString();
    }

    private void appendScopeHints(StringBuilder builder, PlanTaskSession session, List<UserPlanCard> cards) {
        TaskIntakeState intakeState = session == null ? null : session.getIntakeState();
        ReplanScopeEnum scope = intakeState == null ? null : intakeState.getReplanScope();
        if (scope == null) {
            return;
        }
        builder.append("Current replan scope: ").append(scope.name()).append("\n");
        if (hasText(intakeState.getReplanAnchorCardId())) {
            builder.append("Current replan anchorCardId: ").append(intakeState.getReplanAnchorCardId()).append("\n");
        }
        List<String> frozenPrefix = frozenPrefixIds(cards, intakeState.getReplanAnchorCardId());
        if (!frozenPrefix.isEmpty()) {
            builder.append("Frozen completed prefix cardIds: ").append(frozenPrefix).append("\n");
            builder.append("- Do not target, remove, rename, merge, or reorder frozen completed prefix cards.\n");
        }
        if (scope == ReplanScopeEnum.CURRENT_STEP_REDO) {
            builder.append("- CURRENT_STEP_REDO means only the anchor step may be rewritten. You may append helper steps after it, but do not target other existing steps.\n");
        } else if (scope == ReplanScopeEnum.TAIL_REPLAN) {
            builder.append("- TAIL_REPLAN means preserve completed prefix cards and only adjust the anchor step and later tail.\n");
        }
    }

    private PlanPatchIntent clarify(String question) {
        return PlanPatchIntent.builder()
                .operation(PlanPatchOperation.CLARIFY_REQUIRED)
                .confidence(1.0d)
                .clarificationQuestion(question)
                .reason("needs clarification")
                .build();
    }

    private List<UserPlanCard> activeCards(PlanTaskSession session) {
        List<UserPlanCard> cards = session == null ? null : session.getPlanCards();
        if ((cards == null || cards.isEmpty()) && session != null && session.getPlanBlueprint() != null) {
            cards = session.getPlanBlueprint().getPlanCards();
        }
        if (cards == null) {
            return List.of();
        }
        return cards.stream()
                .filter(card -> card != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus()))
                .toList();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> frozenPrefixIds(List<UserPlanCard> cards, String anchorCardId) {
        if (cards == null || cards.isEmpty()) {
            return List.of();
        }
        List<String> frozen = new ArrayList<>();
        for (UserPlanCard card : cards) {
            if (card == null) {
                continue;
            }
            if (hasText(anchorCardId) && anchorCardId.equals(card.getCardId())) {
                break;
            }
            if (!"COMPLETED".equalsIgnoreCase(card.getStatus())) {
                break;
            }
            if (hasText(card.getCardId())) {
                frozen.add(card.getCardId());
            }
        }
        return frozen;
    }

    private boolean passesModelThreshold(PlanPatchIntent intent) {
        return intent.getConfidence() >= plannerProperties.getReplan().getPatchIntentPassThreshold();
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item.asText(null);
            if (hasText(value)) {
                values.add(value.trim());
            }
        });
        return values;
    }

    private PlanPatchOperation parseOperation(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return PlanPatchOperation.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private PlanCardTypeEnum parseType(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return PlanCardTypeEnum.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private List<String> validCardIds(List<UserPlanCard> cards, List<String> candidates) {
        Set<String> allowed = cardIds(cards);
        if (allowed.isEmpty() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<String> valid = new ArrayList<>();
        for (String candidate : candidates) {
            if (hasText(candidate) && allowed.contains(candidate.trim()) && !valid.contains(candidate.trim())) {
                valid.add(candidate.trim());
            }
        }
        return valid;
    }

    private List<String> validFullOrder(List<UserPlanCard> cards, List<String> orderedCardIds) {
        Set<String> allowed = cardIds(cards);
        if (allowed.isEmpty() || orderedCardIds == null || orderedCardIds.size() != allowed.size()) {
            return List.of();
        }
        List<String> valid = validCardIds(cards, orderedCardIds);
        if (valid.size() != allowed.size() || new HashSet<>(valid).size() != valid.size()) {
            return List.of();
        }
        return valid;
    }

    private Set<String> cardIds(List<UserPlanCard> cards) {
        Set<String> ids = new HashSet<>();
        if (cards == null) {
            return ids;
        }
        for (UserPlanCard card : cards) {
            if (card != null && hasText(card.getCardId())) {
                ids.add(card.getCardId());
            }
        }
        return ids;
    }

    private List<PlanPatchCardDraft> validDrafts(List<PlanPatchCardDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        List<PlanPatchCardDraft> valid = new ArrayList<>();
        for (PlanPatchCardDraft draft : drafts) {
            if (draft == null || !hasText(draft.getTitle()) || draft.getType() == null) {
                continue;
            }
            valid.add(PlanPatchCardDraft.builder()
                    .title(draft.getTitle().trim())
                    .description(hasText(draft.getDescription()) ? draft.getDescription().trim() : draft.getTitle().trim())
                    .type(draft.getType())
                    .build());
        }
        return valid;
    }

    private int timeoutSeconds() {
        return Math.max(1, plannerProperties.getReplan().getPatchIntentTimeoutSeconds());
    }
}
