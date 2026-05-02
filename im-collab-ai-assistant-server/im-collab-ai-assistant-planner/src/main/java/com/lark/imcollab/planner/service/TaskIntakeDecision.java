package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;

public record TaskIntakeDecision(
        TaskIntakeTypeEnum intakeType,
        String effectiveInput,
        String routingReason,
        String assistantReply,
        String readOnlyView
) {

    public TaskIntakeDecision(TaskIntakeTypeEnum intakeType, String effectiveInput) {
        this(intakeType, effectiveInput, null, null, null);
    }

    public TaskIntakeDecision(
            TaskIntakeTypeEnum intakeType,
            String effectiveInput,
            String routingReason,
            String assistantReply
    ) {
        this(intakeType, effectiveInput, routingReason, assistantReply, null);
    }
}
