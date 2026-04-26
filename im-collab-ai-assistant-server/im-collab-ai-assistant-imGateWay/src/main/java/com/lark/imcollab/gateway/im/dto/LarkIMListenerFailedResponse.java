package com.lark.imcollab.gateway.im.dto;

public record LarkIMListenerFailedResponse(
        String event,
        String errorType,
        String message,
        boolean retryable
) {
}
