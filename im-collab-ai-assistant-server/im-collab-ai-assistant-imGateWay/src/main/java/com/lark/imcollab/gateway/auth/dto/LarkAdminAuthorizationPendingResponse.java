package com.lark.imcollab.gateway.auth.dto;

public record LarkAdminAuthorizationPendingResponse(
        String event,
        String message
) {
}
