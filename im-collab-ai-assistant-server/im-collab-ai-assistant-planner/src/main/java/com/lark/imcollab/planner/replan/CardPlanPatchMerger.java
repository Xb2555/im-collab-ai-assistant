package com.lark.imcollab.planner.replan;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CardPlanPatchMerger {

    public PlanBlueprint merge(PlanBlueprint existing, PlanPatchIntent intent, String taskId) {
        PlanBlueprint source = existing == null ? new PlanBlueprint() : existing;
        List<UserPlanCard> cards = copyCards(source.getPlanCards(), taskId);
        if (intent == null || intent.getOperation() == null) {
            return rebuild(source, cards);
        }
        switch (intent.getOperation()) {
            case ADD_STEP -> addCards(cards, intent, taskId);
            case REMOVE_STEP -> removeCards(cards, intent);
            case UPDATE_STEP -> updateCards(cards, intent);
            case REORDER_STEP -> cards = reorderCards(cards, intent);
            default -> {
            }
        }
        normalizeCardIds(cards);
        repairDependencies(cards);
        return rebuild(source, cards);
    }

    private void addCards(List<UserPlanCard> cards, PlanPatchIntent intent, String taskId) {
        List<PlanPatchCardDraft> drafts = intent.getNewCardDrafts() == null ? List.of() : intent.getNewCardDrafts();
        for (PlanPatchCardDraft draft : drafts) {
            if (draft == null) {
                continue;
            }
            cards.add(UserPlanCard.builder()
                    .cardId(nextCardId(cards))
                    .taskId(taskId)
                    .title(hasText(draft.getTitle()) ? draft.getTitle().trim() : "新增步骤")
                    .description(draft.getDescription())
                    .type(draft.getType() == null ? PlanCardTypeEnum.SUMMARY : draft.getType())
                    .status("PENDING")
                    .progress(0)
                    .dependsOn(defaultDependsOn(cards))
                    .availableActions(List.of("CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"))
                    .build());
        }
    }

    private void removeCards(List<UserPlanCard> cards, PlanPatchIntent intent) {
        LinkedHashSet<String> targets = ids(intent.getTargetCardIds());
        if (targets.isEmpty()) {
            return;
        }
        cards.removeIf(card -> card != null
                && targets.contains(card.getCardId())
                && !"COMPLETED".equalsIgnoreCase(card.getStatus()));
        for (UserPlanCard card : cards) {
            if (card != null && targets.contains(card.getCardId()) && "COMPLETED".equalsIgnoreCase(card.getStatus())) {
                card.setStatus("SUPERSEDED");
            }
        }
    }

    private void updateCards(List<UserPlanCard> cards, PlanPatchIntent intent) {
        LinkedHashSet<String> targets = ids(intent.getTargetCardIds());
        if (targets.isEmpty()) {
            return;
        }
        PlanPatchCardDraft draft = intent.getNewCardDrafts() == null || intent.getNewCardDrafts().isEmpty()
                ? null
                : intent.getNewCardDrafts().get(0);
        if (draft == null) {
            return;
        }
        for (UserPlanCard card : cards) {
            if (card == null || !targets.contains(card.getCardId())) {
                continue;
            }
            if (hasText(draft.getTitle())) {
                card.setTitle(draft.getTitle().trim());
            }
            if (hasText(draft.getDescription())) {
                card.setDescription(draft.getDescription().trim());
            }
            if (draft.getType() != null) {
                card.setType(draft.getType());
            }
            card.setVersion(card.getVersion() + 1);
        }
    }

    private List<UserPlanCard> reorderCards(List<UserPlanCard> cards, PlanPatchIntent intent) {
        LinkedHashSet<String> order = ids(intent.getOrderedCardIds());
        if (order.isEmpty()) {
            return cards;
        }
        Map<String, UserPlanCard> byId = cards.stream()
                .filter(card -> card != null && hasText(card.getCardId()))
                .collect(Collectors.toMap(UserPlanCard::getCardId, Function.identity(), (left, ignored) -> left));
        List<UserPlanCard> reordered = new ArrayList<>();
        for (String id : order) {
            UserPlanCard card = byId.remove(id);
            if (card != null) {
                reordered.add(card);
            }
        }
        reordered.addAll(byId.values());
        return reordered;
    }

    private PlanBlueprint rebuild(PlanBlueprint source, List<UserPlanCard> cards) {
        return PlanBlueprint.builder()
                .taskBrief(source.getTaskBrief())
                .scenarioPath(source.getScenarioPath())
                .deliverables(extractDeliverables(cards))
                .sourceScope(source.getSourceScope())
                .constraints(source.getConstraints())
                .successCriteria(source.getSuccessCriteria())
                .risks(source.getRisks())
                .planCards(cards)
                .build();
    }

    private List<UserPlanCard> copyCards(List<UserPlanCard> sourceCards, String taskId) {
        if (sourceCards == null) {
            return new ArrayList<>();
        }
        List<UserPlanCard> cards = new ArrayList<>();
        for (UserPlanCard source : sourceCards) {
            if (source == null) {
                continue;
            }
            cards.add(UserPlanCard.builder()
                    .cardId(source.getCardId())
                    .taskId(taskId)
                    .version(source.getVersion())
                    .title(source.getTitle())
                    .description(source.getDescription())
                    .type(source.getType())
                    .status(source.getStatus())
                    .progress(source.getProgress())
                    .artifactRefs(source.getArtifactRefs())
                    .dependsOn(source.getDependsOn() == null ? List.of() : new ArrayList<>(source.getDependsOn()))
                    .availableActions(source.getAvailableActions())
                    .agentTaskPlanCards(source.getAgentTaskPlanCards())
                    .build());
        }
        return cards;
    }

    private void normalizeCardIds(List<UserPlanCard> cards) {
        LinkedHashSet<String> used = new LinkedHashSet<>();
        for (UserPlanCard card : cards) {
            if (card == null) {
                continue;
            }
            if (!hasText(card.getCardId()) || used.contains(card.getCardId())) {
                card.setCardId(nextCardId(used));
            }
            used.add(card.getCardId());
        }
    }

    private void repairDependencies(List<UserPlanCard> cards) {
        LinkedHashSet<String> activeIds = cards.stream()
                .filter(card -> card != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus()) && hasText(card.getCardId()))
                .map(UserPlanCard::getCardId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String previousActiveId = null;
        for (UserPlanCard card : cards) {
            if (card == null || "SUPERSEDED".equalsIgnoreCase(card.getStatus())) {
                continue;
            }
            List<String> fixed = new ArrayList<>();
            for (String dependency : card.getDependsOn() == null ? List.<String>of() : card.getDependsOn()) {
                if (activeIds.contains(dependency) && !dependency.equals(card.getCardId())) {
                    fixed.add(dependency);
                }
            }
            if (fixed.isEmpty() && previousActiveId != null) {
                fixed.add(previousActiveId);
            }
            card.setDependsOn(fixed);
            previousActiveId = card.getCardId();
        }
    }

    private List<String> defaultDependsOn(List<UserPlanCard> cards) {
        for (int index = cards.size() - 1; index >= 0; index--) {
            UserPlanCard card = cards.get(index);
            if (card != null && hasText(card.getCardId()) && !"SUPERSEDED".equalsIgnoreCase(card.getStatus())) {
                return List.of(card.getCardId());
            }
        }
        return List.of();
    }

    private List<String> extractDeliverables(List<UserPlanCard> cards) {
        LinkedHashSet<String> deliverables = new LinkedHashSet<>();
        for (UserPlanCard card : cards == null ? List.<UserPlanCard>of() : cards) {
            if (card != null && card.getType() != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus())) {
                deliverables.add(card.getType().name());
            }
        }
        return new ArrayList<>(deliverables);
    }

    private LinkedHashSet<String> ids(List<String> values) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            if (hasText(value)) {
                ids.add(value.trim());
            }
        }
        return ids;
    }

    private String nextCardId(List<UserPlanCard> cards) {
        LinkedHashSet<String> used = cards.stream()
                .filter(card -> card != null && hasText(card.getCardId()))
                .map(UserPlanCard::getCardId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return nextCardId(used);
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
