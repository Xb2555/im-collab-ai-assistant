package com.lark.imcollab.skills.lark.im;

import java.util.List;

public record ExpandedQueryPlan(
        String originalQuery,
        List<String> expandedQueries,
        String triggerReason
) {
    public ExpandedQueryPlan {
        originalQuery = originalQuery == null ? "" : originalQuery.trim();
        expandedQueries = expandedQueries == null ? List.of() : List.copyOf(expandedQueries);
        triggerReason = triggerReason == null ? "" : triggerReason.trim();
    }

    public static ExpandedQueryPlan empty() {
        return new ExpandedQueryPlan("", List.of(), "not-triggered");
    }
}
