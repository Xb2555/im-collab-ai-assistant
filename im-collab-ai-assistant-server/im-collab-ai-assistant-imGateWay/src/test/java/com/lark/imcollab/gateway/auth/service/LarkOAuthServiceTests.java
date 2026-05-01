package com.lark.imcollab.gateway.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.client.LarkOAuthClient;
import com.lark.imcollab.gateway.auth.config.LarkOAuthProperties;
import com.lark.imcollab.gateway.auth.dto.LarkAuthTokenResponse;
import com.lark.imcollab.gateway.auth.dto.LarkFrontendUserResponse;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthLoginSession;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthTokenPayload;
import com.lark.imcollab.gateway.config.LarkAppProperties;
import com.lark.imcollab.store.redis.RedisJsonStore;
import com.lark.imcollab.store.redis.RedisStringStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LarkOAuthServiceTests {

    private final LarkOAuthProperties oauthProperties = new LarkOAuthProperties();
    private final LarkAppProperties appProperties = new LarkAppProperties();
    private final LarkOAuthClient oauthClient = mock(LarkOAuthClient.class);
    private final RedisStringStore redisStringStore = mock(RedisStringStore.class);
    private final RedisJsonStore redisJsonStore = mock(RedisJsonStore.class);
    private final LarkBusinessJwtService jwtService = new LarkBusinessJwtService(oauthProperties, new ObjectMapper());
    private final LarkOAuthService service = new LarkOAuthService(
            oauthProperties,
            appProperties,
            oauthClient,
            redisStringStore,
            redisJsonStore,
            jwtService
    );

    @Test
    void shouldBuildConsentAuthorizationUrlWithCurrentFeishuParameters() {
        appProperties.setAppId("cli_test");
        oauthProperties.setRedirectUri("http://localhost:8078/api/auth/callback");

        String uri = service.startLogin().authorizationUri().toString();

        assertThat(uri).startsWith("https://accounts.feishu.cn/open-apis/authen/v1/authorize?");
        assertThat(uri).contains("client_id=cli_test");
        assertThat(uri).contains("response_type=code");
        assertThat(uri).contains("redirect_uri=http%3A%2F%2Flocalhost%3A8078%2Fapi%2Fauth%2Fcallback");
        assertThat(uri).doesNotContain("app_id=");
    }

    @Test
    void shouldReturnOpenIdAfterCallbackAndMeLookup() {
        appProperties.setAppSecret("app-secret");
        oauthProperties.setJwtSecret("0123456789abcdef0123456789abcdef");
        oauthProperties.setJwtTtl(Duration.ofMinutes(30));
        when(oauthClient.getAppAccessToken()).thenReturn("app-access-token");
        when(oauthClient.exchangeAuthorizationCode("app-access-token", "code-1"))
                .thenReturn(new LarkOAuthTokenPayload(
                        "user-access-token",
                        7200,
                        "refresh-token",
                        86400,
                        "Bearer",
                        "scope",
                        "ou_current",
                        "on_union",
                        "user_id",
                        "tenant_key",
                        "张三",
                        "https://avatar.example/zhang.png"
                ));

        LarkAuthTokenResponse response = service.completeLogin("code-1", null);

        assertThat(response.user().openId()).isEqualTo("ou_current");

        ArgumentCaptor<LarkOAuthLoginSession> sessionCaptor = ArgumentCaptor.forClass(LarkOAuthLoginSession.class);
        org.mockito.Mockito.verify(redisJsonStore).set(
                org.mockito.ArgumentMatchers.startsWith("imcollab:auth:lark:session:"),
                sessionCaptor.capture(),
                any()
        );
        when(redisJsonStore.get(any(), eq(LarkOAuthLoginSession.class)))
                .thenReturn(Optional.of(sessionCaptor.getValue()));

        Optional<LarkFrontendUserResponse> currentUser =
                service.findCurrentUserByBusinessToken(response.accessToken());

        assertThat(currentUser).isPresent();
        assertThat(currentUser.get().openId()).isEqualTo("ou_current");
    }
}
