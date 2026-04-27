package com.lark.imcollab.gateway.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.client.LarkOAuthClient;
import com.lark.imcollab.gateway.auth.config.LarkOAuthProperties;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthLoginSession;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthTokenPayload;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;
import com.lark.imcollab.gateway.auth.store.LarkUserSessionStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LarkOAuthServiceTests {

    @Test
    void shouldRefreshExpiredAccessTokenWhenSessionHasValidRefreshToken() {
        InMemoryStore store = new InMemoryStore();
        FakeOAuthClient client = new FakeOAuthClient();
        LarkOAuthProperties properties = properties();
        LarkBusinessJwtService jwtService = new LarkBusinessJwtService(properties, new ObjectMapper());
        LarkOAuthService service = new LarkOAuthService(properties, client, store, jwtService);
        LarkOAuthLoginSession session = new LarkOAuthLoginSession(
                "old-token",
                Instant.now().minusSeconds(1),
                "refresh-token",
                Instant.now().plusSeconds(3600),
                "Bearer",
                "docs:doc",
                new LarkOAuthUserResponse("ou_123", "on_123", "user_123", "tenant_123", "User One", null)
        );
        store.saveSession("session-1", session, Duration.ofHours(1));
        String businessJwt = jwtService.issueToken("session-1", session.user(), Duration.ofHours(1));

        Optional<String> accessToken = service.resolveUserAccessTokenByBusinessToken(businessJwt);

        assertThat(accessToken).contains("refreshed-token");
        assertThat(client.refreshToken).isEqualTo("refresh-token");
        assertThat(store.sessions.get("session-1").user().openId()).isEqualTo("ou_123");
    }

    @Test
    void shouldDeleteSessionWhenAccessAndRefreshTokensAreExpired() {
        InMemoryStore store = new InMemoryStore();
        LarkOAuthProperties properties = properties();
        LarkBusinessJwtService jwtService = new LarkBusinessJwtService(properties, new ObjectMapper());
        LarkOAuthService service = new LarkOAuthService(properties, new FakeOAuthClient(), store, jwtService);
        LarkOAuthLoginSession session = new LarkOAuthLoginSession(
                "old-token",
                Instant.now().minusSeconds(1),
                "refresh-token",
                Instant.now().minusSeconds(1),
                "Bearer",
                "docs:doc",
                new LarkOAuthUserResponse("ou_123", "on_123", "user_123", "tenant_123", "User One", null)
        );
        store.saveSession("session-1", session, Duration.ofHours(1));
        String businessJwt = jwtService.issueToken("session-1", session.user(), Duration.ofHours(1));

        Optional<String> accessToken = service.resolveUserAccessTokenByBusinessToken(businessJwt);

        assertThat(accessToken).isEmpty();
        assertThat(store.sessions).doesNotContainKey("session-1");
    }

    private static LarkOAuthProperties properties() {
        LarkOAuthProperties properties = new LarkOAuthProperties();
        properties.setAppId("app_123");
        properties.setAppSecret("secret_123");
        properties.setRedirectUri("http://localhost:8078/api/auth/lark/callback");
        properties.setJwtSecret("test-secret-with-enough-length");
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

    private static final class InMemoryStore implements LarkUserSessionStore {

        private final Map<String, String> states = new HashMap<>();
        private final Map<String, LarkOAuthLoginSession> sessions = new HashMap<>();

        @Override
        public void saveState(String state, Duration ttl) {
            states.put(state, "1");
        }

        @Override
        public boolean consumeState(String state) {
            return states.remove(state) != null;
        }

        @Override
        public void saveSession(String sessionId, LarkOAuthLoginSession session, Duration ttl) {
            sessions.put(sessionId, session);
        }

        @Override
        public Optional<LarkOAuthLoginSession> findSession(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public void deleteSession(String sessionId) {
            sessions.remove(sessionId);
        }
    }
}
