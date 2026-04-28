package com.lark.imcollab.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.AgentTaskPlanCard;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Deprecated
@Slf4j
@RequiredArgsConstructor
public class PlanQualityService {

    private final ObjectMapper objectMapper;

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
                        if (v >= 0 && v <= 100) return v;
                    } catch (NumberFormatException ignored) {}
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
        } catch (Exception ignored) {}
        return List.of();
    }

    public List<UserPlanCard> extractPlanCards(String plannerOutput, String taskId) {
        try {
            JsonNode root = objectMapper.readTree(plannerOutput);
            JsonNode planCardsNode = root.has("planCards") ? root.get("planCards") : root;
            List<UserPlanCard> cards = new ArrayList<>();
            if (planCardsNode.isArray()) {
                for (JsonNode cardNode : planCardsNode) {
                    UserPlanCard card = parsePlanCard(cardNode, taskId);
                    cards.add(card);
                }
            }
            return cards;
        } catch (Exception e) {
            log.warn("Failed to parse planner output as plan cards: {}", e.getMessage());
            return List.of();
        }
    }

    private UserPlanCard parsePlanCard(JsonNode node, String taskId) {
        String cardId = node.has("cardId") ? node.get("cardId").asText() : UUID.randomUUID().toString();
        String title = node.has("title") ? node.get("title").asText() : "未命名任务";
        String description = node.has("description") ? node.get("description").asText() : "";
        PlanCardTypeEnum type = PlanCardTypeEnum.DOC;
        if (node.has("type")) {
            try {
                type = PlanCardTypeEnum.valueOf(node.get("type").asText().toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        List<String> dependsOn = new ArrayList<>();
        if (node.has("dependsOn") && node.get("dependsOn").isArray()) {
            node.get("dependsOn").forEach(n -> dependsOn.add(n.asText()));
        }

        List<String> availableActions = List.of("CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD");

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
                .availableActions(availableActions)
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

    public PlanTaskSession applyPlanReady(PlanTaskSession session, List<UserPlanCard> cards) {
        session.setPlanCards(cards);
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setTransitionReason("Plan generated");
        return session;
    }
}
