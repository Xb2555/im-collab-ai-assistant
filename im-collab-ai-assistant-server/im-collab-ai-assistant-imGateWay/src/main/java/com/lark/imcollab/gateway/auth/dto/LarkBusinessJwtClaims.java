package com.lark.imcollab.gateway.auth.dto;

import java.time.Instant;

public record LarkBusinessJwtClaims(
        String sessionId,
        String subject,
        Instant expiresAt
) {
}
