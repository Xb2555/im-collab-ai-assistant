package com.lark.imcollab.common.model.vo;

import java.time.Instant;

public record TaskStepVO(
        String stepId,
        String name,
        String type,
        String status,
        String inputSummary,
        String outputSummary,
        int progress,
        int retryCount,
        String assignedWorker,
        Instant startedAt,
        Instant endedAt
) {
}
