package com.lark.imcollab.common.model.vo;

public record TriggerGuidanceVO(
        String code,
        String label,
        String suggestedReply,
        String description,
        boolean visible
) {
}
