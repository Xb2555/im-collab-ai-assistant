package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;

public record PlannerToolResult(
        boolean success,
        String taskId,
        PlanningPhaseEnum phase,
        String message,
        Object payload
) {

    public static PlannerToolResult success(String taskId, PlanningPhaseEnum phase, String message, Object payload) {
        return new PlannerToolResult(true, taskId, phase, message, payload);
    }

    public static PlannerToolResult failure(String taskId, PlanningPhaseEnum phase, String message) {
        return new PlannerToolResult(false, taskId, phase, message, null);
    }
}
