package com.lark.imcollab.gateway.auth.dto;

public record LarkOAuthTokenPayload(
        String accessToken,
        long expiresIn,
        String refreshToken,
        long refreshExpiresIn,
        String tokenType,
        String scope,
        String openId,
        String unionId,
        String userId,
        String tenantKey,
        String name,
        String avatarUrl
) {
}
