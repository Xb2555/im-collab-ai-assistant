package com.lark.imcollab.skills.lark.auth.dto;

public record AdminAuthorizationProfile(
        String name,
        String appId,
        String brand,
        boolean active,
        String user
) {
}
