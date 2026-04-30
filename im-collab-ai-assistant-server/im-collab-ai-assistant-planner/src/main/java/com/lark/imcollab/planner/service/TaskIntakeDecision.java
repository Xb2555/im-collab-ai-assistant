package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;

public record TaskIntakeDecision(
        TaskIntakeTypeEnum intakeType,
        String effectiveInput,
        String routingReason,
        String assistantReply
) {

    public TaskIntakeDecision(TaskIntakeTypeEnum intakeType, String effectiveInput) {
        this(intakeType, effectiveInput, null, null);
    }
}
