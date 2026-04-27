package com.lark.imcollab.gateway.im.event;

public record LarkEventSubscriptionStatus(
        String profileName,
        boolean running,
        String state,
        String startedAt,
        String lastError
) {
}
