package com.lark.imcollab.gateway.auth.dto;

public record LarkAdminAuthorizationInfoResponse(
        String event,
        String userOpenId,
        String userName
) {
}
