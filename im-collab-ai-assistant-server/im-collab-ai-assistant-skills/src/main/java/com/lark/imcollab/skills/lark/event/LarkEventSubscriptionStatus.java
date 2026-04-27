package com.lark.imcollab.skills.lark.event;

public record LarkEventSubscriptionStatus(
        String profileName,
        boolean running,
        String state,
        String startedAt,
        String lastError
) {
}
