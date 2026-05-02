package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;

public record PlannerSupervisorDecision(
        PlannerSupervisorAction action,
        String reason
) {

    public static PlannerSupervisorDecision fromIntake(TaskIntakeTypeEnum intakeType, String reason) {
        PlannerSupervisorAction action = switch (intakeType) {
            case NEW_TASK -> PlannerSupervisorAction.NEW_TASK;
            case CLARIFICATION_REPLY -> PlannerSupervisorAction.CLARIFICATION_REPLY;
            case PLAN_ADJUSTMENT -> PlannerSupervisorAction.PLAN_ADJUSTMENT;
            case STATUS_QUERY -> PlannerSupervisorAction.QUERY_STATUS;
            case CONFIRM_ACTION -> PlannerSupervisorAction.CONFIRM_ACTION;
            case CANCEL_TASK -> PlannerSupervisorAction.CANCEL_TASK;
            case UNKNOWN -> PlannerSupervisorAction.UNKNOWN;
        };
        return new PlannerSupervisorDecision(action, reason == null ? "" : reason);
    }
}
