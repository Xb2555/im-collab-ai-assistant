package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;

public record IntentRoutingResult(
        TaskCommandTypeEnum type,
        double confidence,
        String reason,
        String normalizedInput,
        boolean needsClarification,
        String readOnlyView
) {

    public IntentRoutingResult(
            TaskCommandTypeEnum type,
            double confidence,
            String reason,
            String normalizedInput,
            boolean needsClarification
    ) {
        this(type, confidence, reason, normalizedInput, needsClarification, null);
    }
}
