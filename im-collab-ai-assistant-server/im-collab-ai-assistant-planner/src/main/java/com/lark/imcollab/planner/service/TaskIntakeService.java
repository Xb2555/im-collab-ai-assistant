package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import org.springframework.stereotype.Service;

@Service
public class TaskIntakeService {

    public TaskIntakeDecision decide(
            PlanTaskSession session,
            String rawInstruction,
            String userFeedback,
            boolean existingSession
    ) {
        String effectiveInput = hasText(userFeedback) ? userFeedback.trim() : safe(rawInstruction).trim();
        if (!existingSession || session == null) {
            return new TaskIntakeDecision(TaskIntakeTypeEnum.NEW_TASK, effectiveInput);
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.ASK_USER && hasText(effectiveInput)) {
            return new TaskIntakeDecision(TaskIntakeTypeEnum.CLARIFICATION_REPLY, effectiveInput);
        }
        if (isStatusQuery(effectiveInput)) {
            return new TaskIntakeDecision(TaskIntakeTypeEnum.STATUS_QUERY, effectiveInput);
        }
        return new TaskIntakeDecision(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, effectiveInput);
    }

    private boolean isStatusQuery(String input) {
        String normalized = safe(input).toLowerCase();
        return normalized.contains("status")
                || normalized.contains("progress")
                || normalized.contains("进度")
                || normalized.contains("状态");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
