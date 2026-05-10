package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;

public record IntentRoutingResult(
        TaskCommandTypeEnum type,
        double confidence,
        String reason,
        String normalizedInput,
        boolean needsClarification,
        String readOnlyView,
        AdjustmentTargetEnum adjustmentTarget
) {

    public IntentRoutingResult(
            TaskCommandTypeEnum type,
            double confidence,
            String reason,
            String normalizedInput,
            boolean needsClarification
    ) {
        this(type, confidence, reason, normalizedInput, needsClarification, null, null);
    }

    public IntentRoutingResult(
            TaskCommandTypeEnum type,
            double confidence,
            String reason,
            String normalizedInput,
            boolean needsClarification,
            String readOnlyView
    ) {
        this(type, confidence, reason, normalizedInput, needsClarification, readOnlyView, null);
    }
}
