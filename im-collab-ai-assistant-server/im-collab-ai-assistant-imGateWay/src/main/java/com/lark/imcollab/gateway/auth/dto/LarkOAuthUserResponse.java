package com.lark.imcollab.gateway.auth.dto;

public record LarkOAuthUserResponse(
        String openId,
        String unionId,
        String userId,
        String tenantKey,
        String name,
        String avatarUrl
) {
}
