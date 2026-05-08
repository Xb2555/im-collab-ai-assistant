package com.lark.imcollab.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.AgentTaskPlanCard;
import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PromptSlotState;
import com.lark.imcollab.common.model.entity.ScenarioIntegrationHook;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import com.lark.imcollab.common.scenario.ScenarioModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
public class PlanQualityService {

    private final ObjectMapper objectMapper;
    private final List<ScenarioModule> scenarioModules;
    private final ExecutionContractFactory executionContractFactory;

    public PlanQualityService(
            ObjectMapper objectMapper,
            List<ScenarioModule> scenarioModules,
            ExecutionContractFactory executionContractFactory
    ) {
        this.objectMapper = objectMapper;
        this.scenarioModules = scenarioModules == null ? List.of() : scenarioModules;
        this.executionContractFactory = executionContractFactory;
    }

    public int extractScore(String criticOutput) {
        try {
            JsonNode root = objectMapper.readTree(criticOutput);
            if (root.has("overallScore")) {
                return root.get("overallScore").asInt(0);
            }
        } catch (Exception e) {
            log.warn("Failed to parse critic output as JSON, falling back to text scan: {}", e.getMessage());
        }
        String upper = criticOutput.toUpperCase();
        if (upper.contains("OVERALL") || upper.contains("SCORE")) {
            for (String token : criticOutput.split("[^0-9]+")) {
                if (!token.isEmpty()) {
                    try {
                        int v = Integer.parseInt(token);
                        if (v >= 0 && v <= 100) {
                            return v;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return 0;
    }

    public List<String> extractSuggestions(String criticOutput) {
        try {
            JsonNode root = objectMapper.readTree(criticOutput);
            if (root.has("improvementSuggestions")) {
                List<String> suggestions = new ArrayList<>();
                root.get("improvementSuggestions").forEach(n -> suggestions.add(n.asText()));
                return suggestions;
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    public IntentSnapshot extractIntentSnapshot(String output) {
        try {
            JsonNode root = objectMapper.readTree(output);
            return objectMapper.treeToValue(root, IntentSnapshot.class);
        } catch (Exception e) {
            log.warn("Failed to parse intent snapshot: {}", e.getMessage());
            return null;
        }
    }

    public PlanBlueprint extractPlanBlueprint(String plannerOutput, String taskId, IntentSnapshot intentSnapshot) {
        try {
            JsonNode root = objectMapper.readTree(plannerOutput);
            PlanBlueprint blueprint;
            if (root.isArray() || root.has("planCards")) {
                blueprint = objectMapper.treeToValue(root, PlanBlueprint.class);
            } else {
                blueprint = objectMapper.treeToValue(root, PlanBlueprint.class);
            }
            return normalizePlanBlueprint(blueprint, taskId, intentSnapshot);
        } catch (Exception e) {
            log.warn("Failed to parse planner output as plan blueprint: {}", e.getMessage());
            return normalizePlanBlueprint(
                    PlanBlueprint.builder()
                            .planCards(extractPlanCards(plannerOutput, taskId))
                            .build(),
                    taskId,
                    intentSnapshot
            );
        }
    }

    public List<UserPlanCard> extractPlanCards(String plannerOutput, String taskId) {
        try {
            JsonNode root = objectMapper.readTree(plannerOutput);
            JsonNode planCardsNode = root.has("planCards") ? root.get("planCards") : root;
            List<UserPlanCard> cards = new ArrayList<>();
            if (planCardsNode.isArray()) {
                for (JsonNode cardNode : planCardsNode) {
                    cards.add(parsePlanCard(cardNode, taskId));
                }
            }
            return cards;
        } catch (Exception e) {
            log.warn("Failed to parse planner output as plan cards: {}", e.getMessage());
            return List.of();
        }
    }

    public PlanTaskSession applyIntentReady(PlanTaskSession session, IntentSnapshot intentSnapshot) {
        IntentSnapshot normalized = normalizeIntentSnapshot(intentSnapshot);
        session.setIntentSnapshot(normalized);
        session.setScenarioPath(resolveScenarioPath(normalized, null, session.getScenarioPath()));
        session.setActivePromptSlots(buildPromptSlots(normalized));
        session.setPlanningPhase(PlanningPhaseEnum.INTENT_READY);
        session.setTransitionReason("Intent understood");
        return session;
    }

    public PlanTaskSession applyPlanReady(PlanTaskSession session, PlanBlueprint blueprint) {
        PlanBlueprint normalized = normalizePlanBlueprint(blueprint, session.getTaskId(), session.getIntentSnapshot());
        normalized.setTaskBrief(resolveTaskBrief(normalized, session.getIntentSnapshot(), session.getRawInstruction()));
        session.setPlanBlueprint(normalized);
        var contract = executionContractFactory.build(session);
        normalized = executionContractFactory.applyArtifactGate(normalized, contract);
        normalized.setTaskBrief(resolveTaskBrief(normalized, session.getIntentSnapshot(), session.getRawInstruction()));
        session.setPlanBlueprint(normalized);
        contract = executionContractFactory.build(session);
        session.setClarifiedInstruction(contract.getClarifiedInstruction());
        session.setPlanBlueprintSummary(buildBlueprintSummary(normalized));
        session.setPlanCards(normalized.getPlanCards() == null ? List.of() : normalized.getPlanCards());
        session.setScenarioPath(resolveScenarioPath(session.getIntentSnapshot(), normalized, session.getScenarioPath()));
        session.setIntegrationHooks(buildIntegrationHooks(normalized));
        session.setExecutionContract(contract);
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setAborted(false);
        session.setActiveExecutionAttemptId(null);
        session.setPlanVersion(session.getPlanVersion() + 1);
        session.setTransitionReason("Plan generated");
        return session;
    }

    public PlanTaskSession applyPlanAdjustment(
            PlanTaskSession session,
            PlanBlueprint updatedBlueprint,
            String adjustmentInstruction
    ) {
        if (session.getPlanBlueprint() == null) {
            return applyPlanReady(session, updatedBlueprint);
        }

        PlanBlueprint existing = normalizePlanBlueprint(session.getPlanBlueprint(), session.getTaskId(), session.getIntentSnapshot());
        PlanBlueprint updated = normalizePlanBlueprint(updatedBlueprint, session.getTaskId(), session.getIntentSnapshot());
        PlanBlueprint merged = mergePlanBlueprint(existing, updated);
        return applyMergedPlanAdjustment(session, merged, "Plan adjusted");
    }

    public PlanTaskSession applyMergedPlanAdjustment(
            PlanTaskSession session,
            PlanBlueprint mergedBlueprint,
            String reason
    ) {
        PlanBlueprint normalized = normalizePlanBlueprint(mergedBlueprint, session.getTaskId(), session.getIntentSnapshot());
        normalized.setTaskBrief(resolveTaskBrief(normalized, session.getIntentSnapshot(), session.getRawInstruction()));
        session.setPlanBlueprint(normalized);
        var contract = executionContractFactory.build(session);
        normalized = executionContractFactory.applyArtifactGate(normalized, contract);
        normalized = normalizePlanBlueprint(normalized, session.getTaskId(), session.getIntentSnapshot());
        normalized.setTaskBrief(resolveTaskBrief(normalized, session.getIntentSnapshot(), session.getRawInstruction()));
        session.setPlanBlueprint(normalized);
        contract = executionContractFactory.build(session);
        log.info("PLAN_ADJUSTMENT materialized: taskId={}, reason='{}', blueprintTaskBrief='{}', blueprintConstraints={}, intentConstraints={}, contractConstraints={}, contractClarified='{}'",
                session.getTaskId(),
                hasText(reason) ? reason : "Plan adjusted",
                abbreviate(normalized.getTaskBrief()),
                normalized.getConstraints(),
                session.getIntentSnapshot() == null ? null : session.getIntentSnapshot().getConstraints(),
                contract == null ? null : contract.getConstraints(),
                abbreviate(contract == null ? null : contract.getClarifiedInstruction()));
        session.setClarifiedInstruction(contract.getClarifiedInstruction());
        session.setPlanBlueprintSummary(buildBlueprintSummary(normalized));
        session.setPlanCards(normalized.getPlanCards() == null ? List.of() : normalized.getPlanCards());
        session.setScenarioPath(resolveScenarioPath(session.getIntentSnapshot(), normalized, session.getScenarioPath()));
        session.setIntegrationHooks(buildIntegrationHooks(normalized));
        session.setExecutionContract(contract);
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setAborted(false);
        session.setActiveExecutionAttemptId(null);
        session.setPlanVersion(session.getPlanVersion() + 1);
        session.setTransitionReason(hasText(reason) ? reason : "Plan adjusted");
        return session;
    }

    private List<PromptSlotState> buildPromptSlots(IntentSnapshot intentSnapshot) {
        if (intentSnapshot == null || intentSnapshot.getMissingSlots() == null) {
            return List.of();
        }
        List<PromptSlotState> slots = new ArrayList<>();
        int index = 1;
        for (String missingSlot : intentSnapshot.getMissingSlots()) {
            if (missingSlot == null || missingSlot.isBlank()) {
                continue;
            }
            slots.add(PromptSlotState.builder()
                    .slotKey("slot-" + index++)
                    .prompt(missingSlot)
                    .value("")
                    .answered(false)
                    .build());
        }
        return slots;
    }

    private IntentSnapshot normalizeIntentSnapshot(IntentSnapshot intentSnapshot) {
        if (intentSnapshot == null) {
            return null;
        }
        intentSnapshot.setScenarioPath(normalizeScenarioPath(intentSnapshot.getScenarioPath()));
        return intentSnapshot;
    }

    private PlanBlueprint mergePlanBlueprint(PlanBlueprint existing, PlanBlueprint updated) {
        return PlanBlueprint.builder()
                .taskBrief(hasText(updated.getTaskBrief()) ? updated.getTaskBrief() : existing.getTaskBrief())
                .scenarioPath(nonEmpty(updated.getScenarioPath()) ? updated.getScenarioPath() : existing.getScenarioPath())
                .deliverables(nonEmpty(updated.getDeliverables()) ? updated.getDeliverables() : existing.getDeliverables())
                .sourceScope(updated.getSourceScope() != null ? updated.getSourceScope() : existing.getSourceScope())
                .constraints(nonEmpty(updated.getConstraints()) ? updated.getConstraints() : existing.getConstraints())
                .successCriteria(nonEmpty(updated.getSuccessCriteria()) ? updated.getSuccessCriteria() : existing.getSuccessCriteria())
                .risks(nonEmpty(updated.getRisks()) ? updated.getRisks() : existing.getRisks())
                .planCards(nonEmpty(updated.getPlanCards()) ? updated.getPlanCards() : existing.getPlanCards())
                .build();
    }

    private PlanBlueprint normalizePlanBlueprint(PlanBlueprint blueprint, String taskId, IntentSnapshot intentSnapshot) {
        PlanBlueprint candidate = blueprint == null ? new PlanBlueprint() : blueprint;
        List<UserPlanCard> cards = normalizePlanCards(candidate.getPlanCards(), taskId);
        if (candidate.getDeliverables() == null || candidate.getDeliverables().isEmpty()) {
            candidate.setDeliverables(extractDeliverables(cards, intentSnapshot));
        }
        candidate.setScenarioPath(resolveScenarioPath(intentSnapshot, candidate, List.of(ScenarioCodeEnum.A_IM, ScenarioCodeEnum.B_PLANNING)));
        if (candidate.getSourceScope() == null && intentSnapshot != null) {
            candidate.setSourceScope(intentSnapshot.getSourceScope());
        }
        candidate.setPlanCards(cards);
        return candidate;
    }

    private String resolveTaskBrief(PlanBlueprint blueprint, IntentSnapshot intentSnapshot, String rawInstruction) {
        List<UserPlanCard> cards = blueprint == null || blueprint.getPlanCards() == null
                ? List.of()
                : blueprint.getPlanCards().stream()
                .filter(card -> card != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus()))
                .toList();
        if (!cards.isEmpty()) {
            String joinedTitles = cards.stream()
                    .map(UserPlanCard::getTitle)
                    .filter(this::hasText)
                    .distinct()
                    .reduce((left, right) -> left + "；" + right)
                    .orElse("");
            if (hasText(joinedTitles)) {
                return joinedTitles;
            }
        }
        if (blueprint != null && hasText(blueprint.getTaskBrief())) {
            return blueprint.getTaskBrief().trim();
        }
        if (intentSnapshot != null && hasText(intentSnapshot.getUserGoal())) {
            return intentSnapshot.getUserGoal().trim();
        }
        return hasText(rawInstruction) ? rawInstruction.trim() : "";
    }

    private List<UserPlanCard> normalizePlanCards(List<UserPlanCard> cards, String taskId) {
        if (cards == null) {
            return List.of();
        }
        List<UserPlanCard> normalized = new ArrayList<>();
        LinkedHashSet<String> usedCardIds = new LinkedHashSet<>();
        for (UserPlanCard card : cards) {
            if (card == null) {
                continue;
            }
            card.setTaskId(taskId);
            if (card.getCardId() == null || card.getCardId().isBlank() || usedCardIds.contains(card.getCardId())) {
                card.setCardId(nextCardId(usedCardIds));
            }
            usedCardIds.add(card.getCardId());
            if (card.getAvailableActions() == null || card.getAvailableActions().isEmpty()) {
                card.setAvailableActions(List.of("CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"));
            }
            if (card.getAgentTaskPlanCards() != null) {
                card.getAgentTaskPlanCards().forEach(agentCard -> {
                    agentCard.setParentCardId(card.getCardId());
                    if (agentCard.getTaskId() == null || agentCard.getTaskId().isBlank()) {
                        agentCard.setTaskId(UUID.randomUUID().toString());
                    }
                });
            }
            normalized.add(card);
        }
        return normalized;
    }

    private String nextCardId(LinkedHashSet<String> usedCardIds) {
        int max = 0;
        for (String cardId : usedCardIds) {
            if (cardId == null || !cardId.matches("card-\\d+")) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(cardId.substring("card-".length())));
            } catch (NumberFormatException ignored) {
            }
        }
        String candidate;
        do {
            candidate = "card-" + String.format("%03d", ++max);
        } while (usedCardIds.contains(candidate));
        return candidate;
    }

    private List<String> extractDeliverables(List<UserPlanCard> cards, IntentSnapshot intentSnapshot) {
        LinkedHashSet<String> deliverables = new LinkedHashSet<>();
        if (intentSnapshot != null && intentSnapshot.getDeliverableTargets() != null) {
            deliverables.addAll(intentSnapshot.getDeliverableTargets());
        }
        for (UserPlanCard card : cards) {
            if (card.getType() != null) {
                deliverables.add(card.getType().name());
            }
        }
        return new ArrayList<>(deliverables);
    }

    private List<ScenarioCodeEnum> resolveScenarioPath(
            IntentSnapshot intentSnapshot,
            PlanBlueprint planBlueprint,
            List<ScenarioCodeEnum> fallback
    ) {
        LinkedHashSet<ScenarioCodeEnum> path = new LinkedHashSet<>();
        path.add(ScenarioCodeEnum.A_IM);
        path.add(ScenarioCodeEnum.B_PLANNING);
        if (intentSnapshot != null && intentSnapshot.getScenarioPath() != null) {
            path.addAll(normalizeScenarioPath(intentSnapshot.getScenarioPath()));
        }
        if (planBlueprint != null && planBlueprint.getScenarioPath() != null) {
            path.addAll(normalizeScenarioPath(planBlueprint.getScenarioPath()));
        }
        List<UserPlanCard> cards = planBlueprint == null ? List.of() : planBlueprint.getPlanCards();
        if (cards != null) {
            for (UserPlanCard card : cards) {
                if (card.getType() == PlanCardTypeEnum.DOC || card.getType() == PlanCardTypeEnum.SUMMARY) {
                    path.add(ScenarioCodeEnum.C_DOC);
                }
                if (card.getType() == PlanCardTypeEnum.PPT) {
                    path.add(ScenarioCodeEnum.D_PRESENTATION);
                }
            }
        }
        if (path.isEmpty() && fallback != null) {
            path.addAll(normalizeScenarioPath(fallback));
        }
        return new ArrayList<>(path);
    }

    private List<ScenarioCodeEnum> normalizeScenarioPath(List<?> rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<ScenarioCodeEnum> normalized = new LinkedHashSet<>();
        for (Object item : rawPath) {
            ScenarioCodeEnum code = toScenarioCode(item);
            if (code != null) {
                normalized.add(code);
            }
        }
        return new ArrayList<>(normalized);
    }

    private ScenarioCodeEnum toScenarioCode(Object item) {
        if (item instanceof ScenarioCodeEnum scenarioCode) {
            return scenarioCode;
        }
        if (item instanceof String text) {
            String normalized = text.trim();
            if (normalized.isBlank()) {
                return null;
            }
            try {
                return ScenarioCodeEnum.valueOf(normalized.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                log.debug("Ignoring unsupported scenario path item from agent output: {}", text);
            }
        }
        return null;
    }

    private List<ScenarioIntegrationHook> buildIntegrationHooks(PlanBlueprint blueprint) {
        if (blueprint == null) {
            return List.of();
        }
        return scenarioModules.stream()
                .filter(module -> module.supports(blueprint))
                .map(module -> module.buildHook(blueprint))
                .toList();
    }

    private String buildBlueprintSummary(PlanBlueprint blueprint) {
        if (blueprint == null) {
            return "";
        }
        int cardCount = blueprint.getPlanCards() == null ? 0 : blueprint.getPlanCards().size();
        String brief = blueprint.getTaskBrief() == null ? "" : blueprint.getTaskBrief().trim();
        return brief.isBlank() ? "Plan with " + cardCount + " cards" : brief + " (" + cardCount + " cards)";
    }

    private UserPlanCard parsePlanCard(JsonNode node, String taskId) {
        String cardId = node.has("cardId") ? node.get("cardId").asText() : UUID.randomUUID().toString();
        String title = node.has("title") ? node.get("title").asText() : "Untitled Task";
        String description = node.has("description") ? node.get("description").asText() : "";
        PlanCardTypeEnum type = PlanCardTypeEnum.DOC;
        if (node.has("type")) {
            try {
                type = PlanCardTypeEnum.valueOf(node.get("type").asText().toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }

        List<String> dependsOn = new ArrayList<>();
        if (node.has("dependsOn") && node.get("dependsOn").isArray()) {
            node.get("dependsOn").forEach(n -> dependsOn.add(n.asText()));
        }

        List<AgentTaskPlanCard> agentCards = new ArrayList<>();
        if (node.has("agentTaskPlanCards") && node.get("agentTaskPlanCards").isArray()) {
            for (JsonNode agentNode : node.get("agentTaskPlanCards")) {
                agentCards.add(parseAgentTaskCard(agentNode, cardId));
            }
        }

        return UserPlanCard.builder()
                .cardId(cardId)
                .taskId(taskId)
                .title(title)
                .description(description)
                .type(type)
                .status("PENDING")
                .progress(0)
                .dependsOn(dependsOn)
                .availableActions(List.of("CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"))
                .agentTaskPlanCards(agentCards)
                .build();
    }

    private AgentTaskPlanCard parseAgentTaskCard(JsonNode node, String parentCardId) {
        String taskId = node.has("taskId") ? node.get("taskId").asText() : UUID.randomUUID().toString();
        String taskTypeStr = node.has("taskType") ? node.get("taskType").asText() : "INTENT_PARSING";
        com.lark.imcollab.common.model.enums.AgentTaskTypeEnum taskType;
        try {
            taskType = com.lark.imcollab.common.model.enums.AgentTaskTypeEnum.valueOf(taskTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            taskType = com.lark.imcollab.common.model.enums.AgentTaskTypeEnum.INTENT_PARSING;
        }
        List<String> tools = new ArrayList<>();
        if (node.has("tools") && node.get("tools").isArray()) {
            node.get("tools").forEach(n -> tools.add(n.asText()));
        }
        return AgentTaskPlanCard.builder()
                .taskId(taskId)
                .parentCardId(parentCardId)
                .taskType(taskType)
                .status("PENDING")
                .input(node.has("input") ? node.get("input").asText() : "")
                .tools(tools)
                .context(node.has("context") ? node.get("context").asText() : "")
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean nonEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 180) {
            return normalized;
        }
        return normalized.substring(0, 180) + "...";
    }
}
