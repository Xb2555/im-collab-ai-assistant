package com.lark.imcollab.common.cli.auth.dto;

import java.util.List;

public record AdminAuthorizationRequest(
        List<String> scopes,
        List<String> domains,
        boolean recommend
) {

    public boolean hasAuthorizationRange() {
        return (scopes != null && !scopes.isEmpty()) || (domains != null && !domains.isEmpty());
    }
}
