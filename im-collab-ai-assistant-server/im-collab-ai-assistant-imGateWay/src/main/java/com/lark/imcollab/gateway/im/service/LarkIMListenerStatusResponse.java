package com.lark.imcollab.gateway.im.service;

public record LarkIMListenerStatusResponse(
        boolean running,
        String state,
        String startedAt,
        String lastError
) {
}
