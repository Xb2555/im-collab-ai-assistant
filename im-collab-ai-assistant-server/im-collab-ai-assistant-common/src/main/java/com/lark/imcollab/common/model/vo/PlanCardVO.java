package com.lark.imcollab.common.model.vo;

import java.util.List;

public record PlanCardVO(
        String cardId,
        String title,
        String description,
        String type,
        String status,
        int progress,
        List<String> dependsOn
) {
}
