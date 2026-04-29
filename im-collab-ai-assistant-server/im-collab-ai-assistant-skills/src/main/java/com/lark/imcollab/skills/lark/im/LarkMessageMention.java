package com.lark.imcollab.skills.lark.im;

public record LarkMessageMention(
        String key,
        String id,
        String idType,
        String name,
        String tenantKey
) {
}
