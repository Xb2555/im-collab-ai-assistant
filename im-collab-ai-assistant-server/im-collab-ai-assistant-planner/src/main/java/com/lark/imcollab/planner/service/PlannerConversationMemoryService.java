package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.ConversationTurn;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class PlannerConversationMemoryService {

    private final PlannerProperties plannerProperties;

    public PlannerConversationMemoryService(PlannerProperties plannerProperties) {
        this.plannerProperties = plannerProperties;
    }

    public void appendUserTurn(PlanTaskSession session, String content, TaskIntakeTypeEnum intakeType, String source) {
        appendTurn(session, "USER", content, intakeType, source);
    }

    public void appendUserTurnIfLatestDifferent(
            PlanTaskSession session,
            String content,
            TaskIntakeTypeEnum intakeType,
            String source
    ) {
        if (isLatestTurn(session, "USER", content)) {
            return;
        }
        appendUserTurn(session, content, intakeType, source);
    }

    public void appendAssistantTurn(PlanTaskSession session, String content) {
        TaskIntakeTypeEnum intakeType = session == null || session.getIntakeState() == null
                ? null
                : session.getIntakeState().getIntakeType();
        appendTurn(session, "ASSISTANT", content, intakeType, "PLANNER");
    }

    public String renderContext(PlanTaskSession session) {
        if (!isEnabled() || session == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "Original goal", firstNonBlank(session.getRawInstruction(), session.getClarifiedInstruction()));
        appendSection(builder, "Clarified goal", session.getClarifiedInstruction());
        appendSection(builder, "Current phase", session.getPlanningPhase() == null ? "" : session.getPlanningPhase().name());
        appendSection(builder, "Memory summary", session.getConversationSummary());
        appendSection(builder, "Current plan", compactCards(session.getPlanCards()));
        if (session.getClarificationQuestions() != null && !session.getClarificationQuestions().isEmpty()) {
            appendSection(builder, "Pending or previous clarification questions", String.join(" | ", session.getClarificationQuestions()));
        }
        if (session.getClarificationAnswers() != null && !session.getClarificationAnswers().isEmpty()) {
            appendSection(builder, "Clarification answers", String.join(" | ", session.getClarificationAnswers()));
        }
        List<ConversationTurn> turns = session.getConversationTurns() == null ? List.of() : session.getConversationTurns();
        if (!turns.isEmpty()) {
            builder.append("Recent conversation:\n");
            for (ConversationTurn turn : turns) {
                builder.append("- ")
                        .append(nullToEmpty(turn.getRole()))
                        .append(" [")
                        .append(turn.getPhase() == null ? "" : turn.getPhase().name())
                        .append("]: ")
                        .append(truncate(turn.getContent(), maxTurnChars()))
                        .append("\n");
            }
        }
        return truncate(builder.toString().trim(), maxContextChars());
    }

    private void appendTurn(
            PlanTaskSession session,
            String role,
            String content,
            TaskIntakeTypeEnum intakeType,
            String source
    ) {
        if (!isEnabled() || session == null || content == null || content.isBlank()) {
            return;
        }
        List<ConversationTurn> turns = new ArrayList<>(session.getConversationTurns() == null
                ? List.of()
                : session.getConversationTurns());
        turns.add(ConversationTurn.builder()
                .turnId(UUID.randomUUID().toString())
                .role(role)
                .content(truncate(content.trim(), maxTurnChars()))
                .source(firstNonBlank(source, "PLANNER"))
                .phase(session.getPlanningPhase())
                .intakeType(intakeType)
                .createdAt(Instant.now())
                .build());
        compactIfNeeded(session, turns);
    }

    private boolean isLatestTurn(PlanTaskSession session, String role, String content) {
        if (session == null || content == null || content.isBlank()
                || session.getConversationTurns() == null || session.getConversationTurns().isEmpty()) {
            return false;
        }
        ConversationTurn latest = session.getConversationTurns().get(session.getConversationTurns().size() - 1);
        return role.equals(latest.getRole()) && content.trim().equals(latest.getContent());
    }

    private void compactIfNeeded(PlanTaskSession session, List<ConversationTurn> turns) {
        int keep = Math.max(1, plannerProperties.getMemory().getRecentTurns());
        if (turns.size() <= keep) {
            session.setConversationTurns(turns);
            return;
        }
        int overflow = turns.size() - keep;
        List<ConversationTurn> oldTurns = turns.subList(0, overflow);
        StringBuilder summary = new StringBuilder(nullToEmpty(session.getConversationSummary()));
        if (summary.length() > 0) {
            summary.append("\n");
        }
        summary.append("Earlier conversation: ");
        for (int index = 0; index < oldTurns.size(); index++) {
            ConversationTurn turn = oldTurns.get(index);
            if (index > 0) {
                summary.append(" | ");
            }
            summary.append(nullToEmpty(turn.getRole()))
                    .append(": ")
                    .append(truncate(turn.getContent(), 120));
        }
        session.setConversationSummary(truncate(summary.toString(), summaryMaxChars()));
        session.setConversationTurns(new ArrayList<>(turns.subList(overflow, turns.size())));
    }

    private void appendSection(StringBuilder builder, String title, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append(title).append(": ").append(truncate(value.trim(), 800)).append("\n");
    }

    private String compactCards(List<UserPlanCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < cards.size(); index++) {
            UserPlanCard card = cards.get(index);
            if (index > 0) {
                builder.append(" | ");
            }
            builder.append(index + 1)
                    .append(". ")
                    .append(nullToEmpty(card.getCardId()))
                    .append(" ")
                    .append(card.getType() == null ? "" : card.getType().name())
                    .append(" ")
                    .append(nullToEmpty(card.getTitle()));
        }
        return builder.toString();
    }

    private boolean isEnabled() {
        return plannerProperties.getMemory() == null || plannerProperties.getMemory().isEnabled();
    }

    private int maxTurnChars() {
        return Math.max(80, plannerProperties.getMemory().getMaxTurnChars());
    }

    private int maxContextChars() {
        return Math.max(500, plannerProperties.getMemory().getMaxContextChars());
    }

    private int summaryMaxChars() {
        return Math.max(200, plannerProperties.getMemory().getSummaryMaxChars());
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
