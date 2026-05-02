package com.lark.imcollab.planner.supervisor;

import java.util.List;

public record ContextSufficiencyResult(
        boolean sufficient,
        String contextSummary,
        List<String> missingItems,
        String clarificationQuestion,
        String reason
) {

    public static ContextSufficiencyResult sufficient(String contextSummary, String reason) {
        return new ContextSufficiencyResult(true, contextSummary == null ? "" : contextSummary, List.of(), "", reason);
    }

    public static ContextSufficiencyResult insufficient(
            List<String> missingItems,
            String clarificationQuestion,
            String reason
    ) {
        return new ContextSufficiencyResult(
                false,
                "",
                missingItems == null ? List.of() : List.copyOf(missingItems),
                clarificationQuestion == null ? "" : clarificationQuestion,
                reason == null ? "" : reason
        );
    }
}
