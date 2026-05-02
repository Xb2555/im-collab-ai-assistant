package com.lark.imcollab.planner.clarification;

import lombok.Builder;

import java.util.List;

@Builder
public record ClarificationDecision(
        ClarificationAction action,
        List<String> questions,
        String intentSummary,
        double confidence,
        String reason
) {

    public boolean asksUser() {
        return action == ClarificationAction.ASK_USER;
    }

    public String planningInput(String fallback) {
        if (intentSummary != null && !intentSummary.isBlank()) {
            return intentSummary.trim();
        }
        return fallback;
    }
}
