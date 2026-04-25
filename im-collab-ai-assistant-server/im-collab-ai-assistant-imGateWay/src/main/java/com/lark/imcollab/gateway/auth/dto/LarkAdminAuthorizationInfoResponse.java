package com.lark.imcollab.gateway.auth.dto;

import java.util.List;

public record LarkAdminAuthorizationInfoResponse(
        String event,
        String userOpenId,
        String userName,
        List<String> requestedScopes,
        List<String> grantedScopes,
        List<String> newlyGrantedScopes,
        List<String> alreadyGrantedScopes,
        List<String> missingScopes
) {
}
