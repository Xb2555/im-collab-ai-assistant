package com.lark.imcollab.skills.lark.auth.dto;

public record AdminAuthorizationProfileCreateRequest(
        String appId,
        String appSecret,
        String profileName,
        String brand
) {
}
