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
        var contract = executionContractFactory.build(session);
        normalized = executionContractFactory.applyArtifactGate(normalized, contract);
        session.setPlanBlueprint(normalized);
        contract = executionContractFactory.build(session);
        session.setClarifiedInstruction(contract.getClarifiedInstruction());
        session.setPlanBlueprintSummary(buildBlueprintSummary(normalized));
        session.setPlanCards(normalized.getPlanCards() == null ? List.of() : normalized.getPlanCards());
        session.setScenarioPath(resolveScenarioPath(session.getIntentSnapshot(), normalized, session.getScenarioPath()));
        session.setIntegrationHooks(buildIntegrationHooks(normalized));
        session.setExecutionContract(contract);
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
        ensureDeterministicAdditiveCards(merged, adjustmentInstruction);
        merged = normalizePlanBlueprint(merged, session.getTaskId(), session.getIntentSnapshot());
        session.setPlanBlueprint(merged);
        var contract = executionContractFactory.build(session);
        merged = executionContractFactory.applyArtifactGate(merged, contract);
        merged = normalizePlanBlueprint(merged, session.getTaskId(), session.getIntentSnapshot());
        session.setPlanBlueprint(merged);
        contract = executionContractFactory.build(session);
        session.setClarifiedInstruction(contract.getClarifiedInstruction());
        session.setPlanBlueprintSummary(buildBlueprintSummary(merged));
        session.setPlanCards(merged.getPlanCards() == null ? List.of() : merged.getPlanCards());
        session.setScenarioPath(resolveScenarioPath(session.getIntentSnapshot(), merged, session.getScenarioPath()));
        session.setIntegrationHooks(buildIntegrationHooks(merged));
        session.setExecutionContract(contract);
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setTransitionReason("Plan adjusted");
        return session;
    }

    public PlanTaskSession applyMergedPlanAdjustment(
            PlanTaskSession session,
            PlanBlueprint mergedBlueprint,
            String reason
    ) {
        PlanBlueprint normalized = normalizePlanBlueprint(mergedBlueprint, session.getTaskId(), session.getIntentSnapshot());
        session.setPlanBlueprint(normalized);
        var contract = executionContractFactory.build(session);
        normalized = executionContractFactory.applyArtifactGate(normalized, contract);
        normalized = normalizePlanBlueprint(normalized, session.getTaskId(), session.getIntentSnapshot());
        session.setPlanBlueprint(normalized);
        contract = executionContractFactory.build(session);
        session.setClarifiedInstruction(contract.getClarifiedInstruction());
        session.setPlanBlueprintSummary(buildBlueprintSummary(normalized));
        session.setPlanCards(normalized.getPlanCards() == null ? List.of() : normalized.getPlanCards());
        session.setScenarioPath(resolveScenarioPath(session.getIntentSnapshot(), normalized, session.getScenarioPath()));
        session.setIntegrationHooks(buildIntegrationHooks(normalized));
        session.setExecutionContract(contract);
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setTransitionReason(hasText(reason) ? reason : "Plan adjusted");
        return session;
    }

    public boolean tryApplyDeterministicPlanAdjustment(PlanTaskSession session, String adjustmentInstruction) {
        if (session == null || session.getPlanBlueprint() == null) {
            return false;
        }
        if (!isAdditiveAdjustment(adjustmentInstruction) || !isDeterministicAdditiveRequest(adjustmentInstruction)) {
            return false;
        }
        PlanBlueprint existing = normalizePlanBlueprint(session.getPlanBlueprint(), session.getTaskId(), session.getIntentSnapshot());
        PlanBlueprint patch = PlanBlueprint.builder()
                .taskBrief(existing.getTaskBrief())
                .scenarioPath(existing.getScenarioPath())
                .deliverables(existing.getDeliverables())
                .sourceScope(existing.getSourceScope())
                .constraints(existing.getConstraints())
                .successCriteria(existing.getSuccessCriteria())
                .risks(existing.getRisks())
                .planCards(existing.getPlanCards())
                .build();
        applyPlanAdjustment(session, patch, adjustmentInstruction);
        return true;
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
        if (additiveAdjustment) {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            List<UserPlanCard> merged = new ArrayList<>();
            appendPlanCards(merged, seen, existingCards);
            appendPlanCards(merged, seen, updatedCards);
            return merged;
        }
        return updatedCards;
    }

    private void ensureDeterministicAdditiveCards(PlanBlueprint blueprint, String adjustmentInstruction) {
        if (blueprint == null || !isAdditiveAdjustment(adjustmentInstruction) || !isDeterministicAdditiveRequest(adjustmentInstruction)) {
            return;
        }
        List<UserPlanCard> cards = blueprint.getPlanCards() == null
                ? new ArrayList<>()
                : new ArrayList<>(blueprint.getPlanCards());
        if (isGroupProgressSummaryRequest(adjustmentInstruction)) {
            ensureGroupProgressSummaryCard(blueprint, cards);
        }
        if (isOneSentenceSummaryRequest(adjustmentInstruction)) {
            ensureOneSentenceSummaryCard(blueprint, cards);
        }
        if (isBossReportPptRequest(adjustmentInstruction)) {
            ensureBossReportPptCard(blueprint, cards);
        }
    }

    private void ensureGroupProgressSummaryCard(PlanBlueprint blueprint, List<UserPlanCard> cards) {
        if (containsGroupProgressSummaryCard(cards)) {
            blueprint.setPlanCards(cards);
            ensureDeliverable(blueprint, PlanCardTypeEnum.SUMMARY.name());
            return;
        }
        cards.add(UserPlanCard.builder()
                .cardId(nextCardId(cards))
                .title("生成群内项目进展摘要")
                .description("基于已有技术方案文档和汇报材料，生成一段可直接发送到群里的项目进展摘要")
                .type(PlanCardTypeEnum.SUMMARY)
                .status("PENDING")
                .progress(0)
                .dependsOn(summaryDependsOn(cards))
                .availableActions(List.of("CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"))
                .build());
        blueprint.setPlanCards(cards);
        ensureDeliverable(blueprint, PlanCardTypeEnum.SUMMARY.name());
    }

    private void ensureOneSentenceSummaryCard(PlanBlueprint blueprint, List<UserPlanCard> cards) {
        if (containsOneSentenceSummaryCard(cards)) {
            blueprint.setPlanCards(cards);
            ensureDeliverable(blueprint, PlanCardTypeEnum.SUMMARY.name());
            return;
        }
        cards.add(UserPlanCard.builder()
                .cardId(nextCardId(cards))
                .title("生成一句话总结")
                .description("基于已生成的文档、PPT 和项目进展摘要，输出一句可以直接用于汇报结尾的简短总结")
                .type(PlanCardTypeEnum.SUMMARY)
                .status("PENDING")
                .progress(0)
                .dependsOn(summaryDependsOn(cards))
                .availableActions(List.of("CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"))
                .build());
        blueprint.setPlanCards(cards);
        ensureDeliverable(blueprint, PlanCardTypeEnum.SUMMARY.name());
    }

    private void ensureBossReportPptCard(PlanBlueprint blueprint, List<UserPlanCard> cards) {
        if (containsBossReportPptCard(cards)) {
            blueprint.setPlanCards(cards);
            ensureDeliverable(blueprint, PlanCardTypeEnum.PPT.name());
            return;
        }
        cards.add(UserPlanCard.builder()
                .cardId(nextCardId(cards))
                .title("生成老板汇报 PPT")
                .description("基于已有技术方案文档、PPT 初稿和项目进展摘要，生成一份面向老板汇报的 PPT 内容")
                .type(PlanCardTypeEnum.PPT)
                .status("PENDING")
                .progress(0)
                .dependsOn(summaryDependsOn(cards))
                .availableActions(List.of("CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"))
                .build());
        blueprint.setPlanCards(cards);
        ensureDeliverable(blueprint, PlanCardTypeEnum.PPT.name());
    }

    private boolean isDeterministicAdditiveRequest(String instruction) {
        return isDeterministicSummaryRequest(instruction) || isBossReportPptRequest(instruction);
    }

    private boolean isDeterministicSummaryRequest(String instruction) {
        return isGroupProgressSummaryRequest(instruction) || isOneSentenceSummaryRequest(instruction);
    }

    private boolean isGroupProgressSummaryRequest(String instruction) {
        String normalized = normalizeText(instruction);
        if (normalized.isBlank()) {
            return false;
        }
        boolean targetsGroup = normalized.contains("群里")
                || normalized.contains("群内")
                || normalized.contains("发到群")
                || normalized.contains("群聊")
                || normalized.contains("chat");
        boolean targetsSummary = normalized.contains("摘要")
                || normalized.contains("总结")
                || normalized.contains("进展")
                || normalized.contains("progress")
                || normalized.contains("summary");
        return targetsGroup && targetsSummary;
    }

    private boolean isOneSentenceSummaryRequest(String instruction) {
        String normalized = normalizeText(instruction);
        if (normalized.isBlank()) {
            return false;
        }
        boolean targetsOneSentence = normalized.contains("一句话")
                || normalized.contains("一句")
                || normalized.contains("一句话总结")
                || normalized.contains("one sentence")
                || normalized.contains("one-line")
                || normalized.contains("one line");
        boolean targetsSummary = normalized.contains("总结")
                || normalized.contains("摘要")
                || normalized.contains("结论")
                || normalized.contains("summary");
        return targetsOneSentence && targetsSummary;
    }

    private boolean isBossReportPptRequest(String instruction) {
        String normalized = normalizeText(instruction);
        if (normalized.isBlank()) {
            return false;
        }
        boolean targetsBoss = normalized.contains("老板")
                || normalized.contains("管理层")
                || normalized.contains("领导")
                || normalized.contains("boss")
                || normalized.contains("executive");
        boolean targetsPpt = normalized.contains("ppt")
                || normalized.contains("演示稿")
                || normalized.contains("汇报");
        return targetsBoss && targetsPpt;
    }

    private boolean containsGroupProgressSummaryCard(List<UserPlanCard> cards) {
        if (cards == null) {
            return false;
        }
        for (UserPlanCard card : cards) {
            if (card == null) {
                continue;
            }
            String text = normalizeText((card.getTitle() == null ? "" : card.getTitle())
                    + " " + (card.getDescription() == null ? "" : card.getDescription()));
            if (card.getType() == PlanCardTypeEnum.SUMMARY
                    || (text.contains("摘要") || text.contains("总结") || text.contains("进展"))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsOneSentenceSummaryCard(List<UserPlanCard> cards) {
        if (cards == null) {
            return false;
        }
        for (UserPlanCard card : cards) {
            if (card == null) {
                continue;
            }
            String text = normalizeText((card.getTitle() == null ? "" : card.getTitle())
                    + " " + (card.getDescription() == null ? "" : card.getDescription()));
            if (text.contains("一句话") || text.contains("一句") || text.contains("one sentence") || text.contains("one-line")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsBossReportPptCard(List<UserPlanCard> cards) {
        if (cards == null) {
            return false;
        }
        for (UserPlanCard card : cards) {
            if (card == null) {
                continue;
            }
            String text = normalizeText((card.getTitle() == null ? "" : card.getTitle())
                    + " " + (card.getDescription() == null ? "" : card.getDescription()));
            if (card.getType() == PlanCardTypeEnum.PPT
                    && (text.contains("老板") || text.contains("管理层") || text.contains("领导") || text.contains("boss"))) {
                return true;
            }
        }
        return false;
    }

    private String nextCardId(List<UserPlanCard> cards) {
        int max = 0;
        if (cards != null) {
            for (UserPlanCard card : cards) {
                String cardId = card == null ? null : card.getCardId();
                if (cardId == null || !cardId.matches("card-\\d+")) {
                    continue;
                }
                try {
                    max = Math.max(max, Integer.parseInt(cardId.substring("card-".length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return "card-" + String.format("%03d", max + 1);
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

    private List<String> summaryDependsOn(List<UserPlanCard> cards) {
        UserPlanCard dependency = findLastCardOfType(cards, PlanCardTypeEnum.PPT);
        if (dependency == null) {
            dependency = findLastCardOfType(cards, PlanCardTypeEnum.DOC);
        }
        if (dependency == null && cards != null) {
            for (int index = cards.size() - 1; index >= 0; index--) {
                UserPlanCard card = cards.get(index);
                if (card != null && card.getType() != PlanCardTypeEnum.SUMMARY && card.getCardId() != null && !card.getCardId().isBlank()) {
                    dependency = card;
                    break;
                }
            }
        }
        return dependency == null || dependency.getCardId() == null || dependency.getCardId().isBlank()
                ? List.of()
                : List.of(dependency.getCardId());
    }

    private UserPlanCard findLastCardOfType(List<UserPlanCard> cards, PlanCardTypeEnum type) {
        if (cards == null) {
            return null;
        }
        for (int index = cards.size() - 1; index >= 0; index--) {
            UserPlanCard card = cards.get(index);
            if (card != null && card.getType() == type && card.getCardId() != null && !card.getCardId().isBlank()) {
                return card;
            }
        }
        return null;
    }

    private void ensureDeliverable(PlanBlueprint blueprint, String deliverable) {
        if (blueprint == null || deliverable == null || deliverable.isBlank()) {
            return;
        }
        LinkedHashSet<String> deliverables = new LinkedHashSet<>();
        if (blueprint.getDeliverables() != null) {
            for (String existing : blueprint.getDeliverables()) {
                if (existing != null && !existing.isBlank()) {
                    deliverables.add(existing.trim());
                }
            }
        }
        deliverables.add(deliverable);
        blueprint.setDeliverables(new ArrayList<>(deliverables));
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
