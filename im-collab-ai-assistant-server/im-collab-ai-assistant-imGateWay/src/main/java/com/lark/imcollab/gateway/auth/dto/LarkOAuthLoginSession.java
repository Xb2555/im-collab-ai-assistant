package com.lark.imcollab.gateway.auth.dto;

import java.time.Instant;

public record LarkOAuthLoginSession(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        String tokenType,
        String scope,
        LarkOAuthUserResponse user
) {
}
