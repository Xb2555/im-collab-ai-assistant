package com.lark.imcollab.gateway.auth.dto;

public record LarkFrontendUserResponse(
        String openId,
        String name,
        String avatarUrl
) {
}
