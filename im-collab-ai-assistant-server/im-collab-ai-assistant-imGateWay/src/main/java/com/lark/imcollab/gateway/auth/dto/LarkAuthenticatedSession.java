package com.lark.imcollab.gateway.auth.dto;

public record LarkAuthenticatedSession(
        String accessToken,
        LarkOAuthUserResponse user
) {
}
