package com.lark.imcollab.gateway.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.client.LarkOAuthClient;
import com.lark.imcollab.gateway.auth.config.LarkOAuthProperties;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthLoginSession;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthTokenPayload;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;
import com.lark.imcollab.gateway.config.LarkAppProperties;
import com.lark.imcollab.store.redis.RedisJsonStore;
import com.lark.imcollab.store.redis.RedisStringStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LarkOAuthServiceTests {

    private static final String SESSION_KEY_PREFIX = "imcollab:auth:lark:session:";

    @Test
    void shouldRefreshExpiredAccessTokenWhenSessionHasValidRefreshToken() {
        InMemoryStore store = new InMemoryStore();
        FakeOAuthClient client = new FakeOAuthClient();
        LarkOAuthProperties properties = properties();
        LarkBusinessJwtService jwtService = new LarkBusinessJwtService(properties, new ObjectMapper());
        LarkOAuthService service = new LarkOAuthService(properties, appProperties(), client, store, store, jwtService);
        LarkOAuthLoginSession session = new LarkOAuthLoginSession(
                "old-token",
                Instant.now().minusSeconds(1),
                "refresh-token",
                Instant.now().plusSeconds(3600),
                "Bearer",
                "docs:doc",
                new LarkOAuthUserResponse("ou_123", "on_123", "user_123", "tenant_123", "User One", null)
        );
        store.set(SESSION_KEY_PREFIX + "session-1", session, Duration.ofHours(1));
        String businessJwt = jwtService.issueToken("session-1", session.user(), Duration.ofHours(1));

        Optional<String> accessToken = service.resolveUserAccessTokenByBusinessToken(businessJwt);

        assertThat(accessToken).contains("refreshed-token");
        assertThat(client.refreshToken).isEqualTo("refresh-token");
        assertThat(store.sessions.get(SESSION_KEY_PREFIX + "session-1").user().openId()).isEqualTo("ou_123");
    }

    @Test
    void shouldDeleteSessionWhenAccessAndRefreshTokensAreExpired() {
        InMemoryStore store = new InMemoryStore();
        LarkOAuthProperties properties = properties();
        LarkBusinessJwtService jwtService = new LarkBusinessJwtService(properties, new ObjectMapper());
        LarkOAuthService service = new LarkOAuthService(properties, appProperties(), new FakeOAuthClient(), store, store,
                jwtService);
        LarkOAuthLoginSession session = new LarkOAuthLoginSession(
                "old-token",
                Instant.now().minusSeconds(1),
                "refresh-token",
                Instant.now().minusSeconds(1),
                "Bearer",
                "docs:doc",
                new LarkOAuthUserResponse("ou_123", "on_123", "user_123", "tenant_123", "User One", null)
        );
        store.set(SESSION_KEY_PREFIX + "session-1", session, Duration.ofHours(1));
        String businessJwt = jwtService.issueToken("session-1", session.user(), Duration.ofHours(1));

        Optional<String> accessToken = service.resolveUserAccessTokenByBusinessToken(businessJwt);

        assertThat(accessToken).isEmpty();
        assertThat(store.sessions).doesNotContainKey(SESSION_KEY_PREFIX + "session-1");
    }

    private static LarkOAuthProperties properties() {
        LarkOAuthProperties properties = new LarkOAuthProperties();
        properties.setRedirectUri("http://localhost:8078/api/auth/lark/callback");
        properties.setJwtSecret("test-secret-with-enough-length");
        return properties;
    }

    private static LarkAppProperties appProperties() {
        LarkAppProperties properties = new LarkAppProperties();
        properties.setAppId("app_123");
        properties.setAppSecret("secret_123");
        return properties;
    }

    private static final class FakeOAuthClient implements LarkOAuthClient {

        private String refreshToken;

        @Override
        public String getAppAccessToken() {
            return "app-access-token";
        }

        @Override
        public LarkOAuthTokenPayload exchangeAuthorizationCode(String appAccessToken, String code) {
            return null;
        }

        @Override
        public LarkOAuthTokenPayload refreshUserAccessToken(String appAccessToken, String refreshToken) {
            this.refreshToken = refreshToken;
            return new LarkOAuthTokenPayload(
                    "refreshed-token",
                    3600,
                    "refresh-token-2",
                    7200,
                    "Bearer",
                    "docs:doc",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    private static final class InMemoryStore implements RedisStringStore, RedisJsonStore {

        private final Map<String, String> states = new HashMap<>();
        private final Map<String, LarkOAuthLoginSession> sessions = new HashMap<>();

        @Override
        public void set(String key, String value, Duration ttl) {
            states.put(key, value);
        }

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(states.get(key));
        }

        @Override
        public boolean hasKey(String key) {
            return states.containsKey(key);
        }

        @Override
        public void set(String key, Object value, Duration ttl) {
            sessions.put(key, (LarkOAuthLoginSession) value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> get(String key, Class<T> type) {
            return Optional.ofNullable((T) sessions.get(key));
        }

        @Override
        public void delete(String key) {
            states.remove(key);
            sessions.remove(key);
        }
    }
}
