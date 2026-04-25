package com.lark.imcollab.skills.lark.auth.dto;

import java.util.List;

public record AdminAuthorizationStatus(
        String appId,
        String brand,
        String defaultAs,
        String identity,
        String tokenStatus,
        String userName,
        String userOpenId,
        String grantedAt,
        String expiresAt,
        String refreshExpiresAt,
        List<String> scopes
) {
}
