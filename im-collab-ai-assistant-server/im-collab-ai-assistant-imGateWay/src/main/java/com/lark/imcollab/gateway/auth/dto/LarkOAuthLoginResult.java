package com.lark.imcollab.gateway.auth.dto;

import java.net.URI;

public record LarkOAuthLoginResult(
        URI authorizationUri,
        String state
) {
}
