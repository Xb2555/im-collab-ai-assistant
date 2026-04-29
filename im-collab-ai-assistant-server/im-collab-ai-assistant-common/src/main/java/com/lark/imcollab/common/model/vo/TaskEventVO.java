package com.lark.imcollab.common.model.vo;

import java.time.Instant;

public record TaskEventVO(
        String eventId,
        String type,
        String stepId,
        String message,
        Instant createdAt
) {
}
