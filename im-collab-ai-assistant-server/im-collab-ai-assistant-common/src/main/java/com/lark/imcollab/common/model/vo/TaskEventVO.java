package com.lark.imcollab.common.model.vo;

import java.time.Instant;

public record TaskEventVO(
        String eventId,
        int version,
        String type,
        String stepId,
        String message,
        Instant createdAt
) {
}
