package com.lark.imcollab.gateway.auth.client;

import com.lark.imcollab.gateway.auth.dto.LarkOAuthTokenPayload;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;

public interface LarkOAuthClient {

    String getAppAccessToken();

    LarkOAuthTokenPayload exchangeAuthorizationCode(String appAccessToken, String code, String redirectUri);

    LarkOAuthTokenPayload refreshUserAccessToken(String appAccessToken, String refreshToken);

    LarkOAuthUserResponse fetchCurrentUser(String userAccessToken);
}
