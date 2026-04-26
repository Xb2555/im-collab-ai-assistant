package com.lark.imcollab.gateway.auth.dto;

public record LarkAdminAuthorizationFailedResponse(
        String event,
        String errorType,
        String message,
        boolean retryable
) {

    public LarkAdminAuthorizationFailedResponse(String event, String message, boolean retryable) {
        this(event, null, message, retryable);
    }
}
