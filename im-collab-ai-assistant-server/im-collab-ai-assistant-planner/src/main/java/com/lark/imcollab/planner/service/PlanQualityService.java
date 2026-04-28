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
import java.util.UUID;

@Slf4j
@Service
public class PlanQualityService {

    private final ObjectMapper objectMapper;
    private final List<ScenarioModule> scenarioModules;

    public PlanQualityService(ObjectMapper objectMapper, List<ScenarioModule> scenarioModules) {
        this.objectMapper = objectMapper;
        this.scenarioModules = scenarioModules == null ? List.of() : scenarioModules;
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
        session.setIntentSnapshot(intentSnapshot);
        session.setScenarioPath(resolveScenarioPath(intentSnapshot, null, session.getScenarioPath()));
        session.setActivePromptSlots(buildPromptSlots(intentSnapshot));
        session.setPlanningPhase(PlanningPhaseEnum.INTENT_READY);
        session.setTransitionReason("Intent understood");
        return session;
    }

    public PlanTaskSession applyPlanReady(PlanTaskSession session, PlanBlueprint blueprint) {
        PlanBlueprint normalized = normalizePlanBlueprint(blueprint, session.getTaskId(), session.getIntentSnapshot());
        session.setPlanBlueprint(normalized);
        session.setPlanBlueprintSummary(buildBlueprintSummary(normalized));
        session.setPlanCards(normalized.getPlanCards() == null ? List.of() : normalized.getPlanCards());
        session.setScenarioPath(resolveScenarioPath(session.getIntentSnapshot(), normalized, session.getScenarioPath()));
        session.setIntegrationHooks(buildIntegrationHooks(normalized));
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
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
        PlanBlueprint merged = mergePlanBlueprint(existing, updated, adjustmentInstruction);

        session.setPlanBlueprint(merged);
        session.setPlanBlueprintSummary(buildBlueprintSummary(merged));
        session.setPlanCards(merged.getPlanCards() == null ? List.of() : merged.getPlanCards());
        session.setScenarioPath(resolveScenarioPath(session.getIntentSnapshot(), merged, session.getScenarioPath()));
        session.setIntegrationHooks(buildIntegrationHooks(merged));
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setTransitionReason("Plan adjusted");
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

    private PlanBlueprint mergePlanBlueprint(
            PlanBlueprint existing,
            PlanBlueprint updated,
            String adjustmentInstruction
    ) {
        boolean additiveAdjustment = isAdditiveAdjustment(adjustmentInstruction);
        return PlanBlueprint.builder()
                .taskBrief(mergeTaskBrief(existing.getTaskBrief(), updated.getTaskBrief(), adjustmentInstruction, additiveAdjustment))
                .scenarioPath(mergeStringEnumList(existing.getScenarioPath(), updated.getScenarioPath()))
                .deliverables(mergeStringList(existing.getDeliverables(), updated.getDeliverables(), adjustmentInstruction, additiveAdjustment, "交付", "输出", "deliverable"))
                .sourceScope(updated.getSourceScope() != null ? updated.getSourceScope() : existing.getSourceScope())
                .constraints(mergeStringList(existing.getConstraints(), updated.getConstraints(), adjustmentInstruction, additiveAdjustment, "约束", "限制", "constraint"))
                .successCriteria(mergeStringList(existing.getSuccessCriteria(), updated.getSuccessCriteria(), adjustmentInstruction, additiveAdjustment, "成功标准", "标准", "criteria"))
                .risks(mergeStringList(existing.getRisks(), updated.getRisks(), adjustmentInstruction, additiveAdjustment, "风险", "risk"))
                .planCards(mergePlanCards(existing.getPlanCards(), updated.getPlanCards(), adjustmentInstruction, additiveAdjustment))
                .build();
    }

    private PlanBlueprint normalizePlanBlueprint(PlanBlueprint blueprint, String taskId, IntentSnapshot intentSnapshot) {
        PlanBlueprint candidate = blueprint == null ? new PlanBlueprint() : blueprint;
        List<UserPlanCard> cards = normalizePlanCards(candidate.getPlanCards(), taskId);
        if (candidate.getDeliverables() == null || candidate.getDeliverables().isEmpty()) {
            candidate.setDeliverables(extractDeliverables(cards, intentSnapshot));
        }
        if (candidate.getScenarioPath() == null || candidate.getScenarioPath().isEmpty()) {
            candidate.setScenarioPath(resolveScenarioPath(intentSnapshot, candidate, List.of(ScenarioCodeEnum.A_IM, ScenarioCodeEnum.B_PLANNING)));
        }
        if (candidate.getSourceScope() == null && intentSnapshot != null) {
            candidate.setSourceScope(intentSnapshot.getSourceScope());
        }
        if ((candidate.getTaskBrief() == null || candidate.getTaskBrief().isBlank()) && intentSnapshot != null) {
            candidate.setTaskBrief(intentSnapshot.getUserGoal());
        }
        candidate.setPlanCards(cards);
        return candidate;
    }

    private List<UserPlanCard> normalizePlanCards(List<UserPlanCard> cards, String taskId) {
        if (cards == null) {
            return List.of();
        }
        return cards.stream().map(card -> {
            card.setTaskId(taskId);
            if (card.getCardId() == null || card.getCardId().isBlank()) {
                card.setCardId(UUID.randomUUID().toString());
            }
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
            return card;
        }).toList();
    }

    private String mergeTaskBrief(
            String existingTaskBrief,
            String updatedTaskBrief,
            String adjustmentInstruction,
            boolean additiveAdjustment
    ) {
        if (updatedTaskBrief == null || updatedTaskBrief.isBlank()) {
            return existingTaskBrief;
        }
        if (existingTaskBrief == null || existingTaskBrief.isBlank()) {
            return updatedTaskBrief;
        }
        if (!targetsField(adjustmentInstruction, "任务", "目标", "brief", "task")) {
            if (additiveAdjustment || looksLikeEditInstruction(updatedTaskBrief, adjustmentInstruction)) {
                return existingTaskBrief;
            }
        }
        return updatedTaskBrief;
    }

    private List<ScenarioCodeEnum> mergeStringEnumList(
            List<ScenarioCodeEnum> existingValues,
            List<ScenarioCodeEnum> updatedValues
    ) {
        if (updatedValues == null || updatedValues.isEmpty()) {
            return existingValues == null ? List.of() : existingValues;
        }
        return updatedValues;
    }

    private List<String> mergeStringList(
            List<String> existingValues,
            List<String> updatedValues,
            String adjustmentInstruction,
            boolean additiveAdjustment,
            String... fieldKeywords
    ) {
        if (updatedValues == null || updatedValues.isEmpty()) {
            return existingValues == null ? List.of() : existingValues;
        }
        boolean targeted = targetsField(adjustmentInstruction, fieldKeywords);
        if (additiveAdjustment && !targeted && existingValues != null && !existingValues.isEmpty()) {
            return existingValues;
        }
        if (additiveAdjustment && targeted) {
            return mergeDistinctStrings(existingValues, updatedValues);
        }
        return updatedValues;
    }

    private List<UserPlanCard> mergePlanCards(
            List<UserPlanCard> existingCards,
            List<UserPlanCard> updatedCards,
            String adjustmentInstruction,
            boolean additiveAdjustment
    ) {
        if (updatedCards == null || updatedCards.isEmpty()) {
            return existingCards == null ? List.of() : existingCards;
        }
        boolean targeted = targetsField(adjustmentInstruction, "步骤", "计划", "卡片", "plan card", "step");
        if (additiveAdjustment && !targeted && existingCards != null && !existingCards.isEmpty()) {
            return existingCards;
        }
        if (additiveAdjustment && targeted) {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            List<UserPlanCard> merged = new ArrayList<>();
            appendPlanCards(merged, seen, existingCards);
            appendPlanCards(merged, seen, updatedCards);
            return merged;
        }
        return updatedCards;
    }

    private void appendPlanCards(List<UserPlanCard> merged, LinkedHashSet<String> seen, List<UserPlanCard> cards) {
        if (cards == null) {
            return;
        }
        for (UserPlanCard card : cards) {
            if (card == null) {
                continue;
            }
            String key = (card.getType() == null ? "" : card.getType().name()) + "::"
                    + (card.getTitle() == null ? "" : card.getTitle().trim());
            if (seen.add(key)) {
                merged.add(card);
            }
        }
    }

    private List<String> mergeDistinctStrings(List<String> existingValues, List<String> updatedValues) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existingValues != null) {
            for (String value : existingValues) {
                if (value != null && !value.isBlank()) {
                    merged.add(value.trim());
                }
            }
        }
        if (updatedValues != null) {
            for (String value : updatedValues) {
                if (value != null && !value.isBlank()) {
                    merged.add(value.trim());
                }
            }
        }
        return new ArrayList<>(merged);
    }

    private boolean isAdditiveAdjustment(String instruction) {
        String normalized = normalizeText(instruction);
        return normalized.contains("再加")
                || normalized.contains("新增")
                || normalized.contains("补充")
                || normalized.contains("追加")
                || normalized.contains("加一条")
                || normalized.contains("append")
                || normalized.contains("add")
                || normalized.contains("include");
    }

    private boolean looksLikeEditInstruction(String candidate, String instruction) {
        String normalizedCandidate = normalizeText(candidate);
        String normalizedInstruction = normalizeText(instruction);
        return !normalizedCandidate.isBlank()
                && !normalizedInstruction.isBlank()
                && (normalizedCandidate.equals(normalizedInstruction)
                || normalizedCandidate.contains("再加")
                || normalizedCandidate.contains("新增")
                || normalizedCandidate.contains("补充")
                || normalizedCandidate.contains("追加"));
    }

    private boolean targetsField(String instruction, String... keywords) {
        String normalized = normalizeText(instruction);
        if (normalized.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (normalized.contains(normalizeText(keyword))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase();
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
            path.addAll(intentSnapshot.getScenarioPath());
        }
        if (planBlueprint != null && planBlueprint.getScenarioPath() != null) {
            path.addAll(planBlueprint.getScenarioPath());
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
            path.addAll(fallback);
        }
        return new ArrayList<>(path);
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
}
