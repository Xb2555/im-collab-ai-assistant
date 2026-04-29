package com.lark.imcollab.common.model.vo;

import java.time.Instant;

public record TaskArtifactVO(
        String artifactId,
        String type,
        String title,
        String url,
        String preview,
        String status,
        Instant createdAt
) {
}
