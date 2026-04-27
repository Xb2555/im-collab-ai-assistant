package com.lark.imcollab.gateway.im.service;

public record LarkIMListenerStatusResponse(
        String profileName,
        boolean running,
        String state,
        String startedAt,
        String lastError
) {
}
