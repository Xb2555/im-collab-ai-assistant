package com.lark.imcollab.gateway.im.event;

public record LarkEventSubscriptionStatus(
        boolean running,
        String state,
        String startedAt,
        String lastError
) {
}
