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
        if (isCancelCommand(effectiveInput)) {
            return new TaskIntakeDecision(TaskIntakeTypeEnum.CANCEL_TASK, effectiveInput);
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
                || normalized.contains("\u8fdb\u5ea6")
                || normalized.contains("\u72b6\u6001");
    }

    private boolean isCancelCommand(String input) {
        String normalized = safe(input).toLowerCase();
        return normalized.contains("\u53d6\u6d88\u4efb\u52a1")
                || normalized.contains("\u53d6\u6d88\u8fd9\u4e2a\u4efb\u52a1")
                || normalized.contains("\u53d6\u6d88\u5f53\u524d\u4efb\u52a1")
                || normalized.contains("\u505c\u6b62\u4efb\u52a1")
                || normalized.contains("\u7ec8\u6b62\u4efb\u52a1")
                || normalized.contains("\u4e0d\u8981\u505a\u4e86")
                || normalized.contains("\u4e0d\u7528\u505a\u4e86")
                || normalized.contains("abort task")
                || normalized.contains("cancel task")
                || normalized.contains("stop task");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
