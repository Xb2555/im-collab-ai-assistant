package com.lark.imcollab.gateway.auth.client;

import com.lark.imcollab.gateway.auth.dto.LarkOAuthTokenPayload;

public interface LarkOAuthClient {

    String getAppAccessToken();

    LarkOAuthTokenPayload exchangeAuthorizationCode(String appAccessToken, String code);

    LarkOAuthTokenPayload refreshUserAccessToken(String appAccessToken, String refreshToken);
}
