package com.lark.imcollab.common.model.vo;

import java.time.Instant;
import java.util.List;

public record TaskSummaryVO(
        String taskId,
        int version,
        String title,
        String goal,
        String status,
        String currentStage,
        int progress,
        boolean needUserAction,
        List<String> riskFlags,
        Instant createdAt,
        Instant updatedAt
) {
}
