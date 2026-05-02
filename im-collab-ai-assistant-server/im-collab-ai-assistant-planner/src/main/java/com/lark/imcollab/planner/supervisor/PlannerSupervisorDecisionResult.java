package com.lark.imcollab.planner.supervisor;

import lombok.Builder;

@Builder
public record PlannerSupervisorDecisionResult(
        PlannerSupervisorAction action,
        double confidence,
        String reason,
        boolean needsClarification,
        String clarificationQuestion,
        String userFacingReply
) {

    public static PlannerSupervisorDecisionResult of(PlannerSupervisorAction action, double confidence, String reason) {
        return PlannerSupervisorDecisionResult.builder()
                .action(action)
                .confidence(confidence)
                .reason(reason == null ? "" : reason)
                .build();
    }
}
