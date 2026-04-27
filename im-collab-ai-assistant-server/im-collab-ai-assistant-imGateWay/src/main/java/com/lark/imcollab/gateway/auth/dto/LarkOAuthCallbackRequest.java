package com.lark.imcollab.gateway.auth.dto;

public record LarkOAuthCallbackRequest(
        String code,
        String state
) {
}
