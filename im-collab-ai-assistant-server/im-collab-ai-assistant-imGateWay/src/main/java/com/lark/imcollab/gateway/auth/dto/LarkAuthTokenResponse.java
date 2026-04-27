package com.lark.imcollab.gateway.auth.dto;

public record LarkAuthTokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        LarkFrontendUserResponse user
) {
}
