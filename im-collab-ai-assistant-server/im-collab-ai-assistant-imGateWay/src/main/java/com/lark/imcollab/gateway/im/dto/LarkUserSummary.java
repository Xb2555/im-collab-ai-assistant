package com.lark.imcollab.gateway.im.dto;

public record LarkUserSummary(
        String openId,
        String userId,
        String unionId,
        String name,
        String enName,
        String email,
        String avatarUrl
) {
}
