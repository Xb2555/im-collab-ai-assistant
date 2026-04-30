package com.lark.imcollab.gateway.im.service;

public record LarkUserProfile(
        String openId,
        String name,
        String avatarUrl
) {
}
