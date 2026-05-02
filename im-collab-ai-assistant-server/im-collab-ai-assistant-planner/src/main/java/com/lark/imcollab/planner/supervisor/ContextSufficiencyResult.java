package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.ContextAcquisitionPlan;

import java.util.List;

public record ContextSufficiencyResult(
        boolean sufficient,
        String contextSummary,
        List<String> missingItems,
        String clarificationQuestion,
        String reason,
        boolean collectionRequired,
        ContextAcquisitionPlan acquisitionPlan
) {

    public static ContextSufficiencyResult sufficient(String contextSummary, String reason) {
        return new ContextSufficiencyResult(
                true,
                contextSummary == null ? "" : contextSummary,
                List.of(),
                "",
                reason,
                false,
                null
        );
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
                reason == null ? "" : reason,
                false,
                null
        );
    }

    public static ContextSufficiencyResult collect(
            ContextAcquisitionPlan acquisitionPlan,
            String reason
    ) {
        return new ContextSufficiencyResult(
                false,
                "",
                List.of("source_context"),
                acquisitionPlan == null ? "" : acquisitionPlan.getClarificationQuestion(),
                reason == null ? "" : reason,
                true,
                acquisitionPlan
        );
    }
}
