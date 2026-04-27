package com.lark.imcollab.gateway.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.client.LarkOAuthClient;
import com.lark.imcollab.gateway.auth.config.LarkOAuthProperties;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthLoginSession;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthTokenPayload;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;
import com.lark.imcollab.gateway.config.LarkAppProperties;
import com.lark.imcollab.gateway.auth.service.LarkBusinessJwtService;
import com.lark.imcollab.gateway.auth.service.LarkOAuthService;
import com.lark.imcollab.store.redis.RedisJsonStore;
import com.lark.imcollab.store.redis.RedisStringStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LarkOAuthControllerTests {

    private static final String STATE_KEY_PREFIX = "imcollab:auth:lark:state:";
    private static final String SESSION_KEY_PREFIX = "imcollab:auth:lark:session:";

    @Test
    void shouldRedirectToLarkAuthorizationPage() throws Exception {
        TestFixture fixture = new TestFixture();

        fixture.mockMvc.perform(get("/api/auth/lark/login"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.containsString(
                        "https://open.feishu.cn/open-apis/authen/v1/authorize")))
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.containsString("app_id=app_123")))
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.containsString("redirect_uri=")))
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.containsString("scope=")))
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.containsString("contact:user:search")))
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.containsString("im:message.send_as_user")))
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.containsString("state=")));

        assertThat(fixture.store.states).hasSize(1);
    }

    @Test
    void shouldExchangeCodeCreateSessionAndReturnBusinessJwt() throws Exception {
        TestFixture fixture = new TestFixture();
        fixture.store.set(STATE_KEY_PREFIX + "state-1", "1", Duration.ofMinutes(10));

        MvcResult callback = fixture.mockMvc.perform(post("/api/auth/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"code-1","state":"state-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.name").value("User One"))
                .andExpect(jsonPath("$.data.user.avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.data.user.openId").doesNotExist())
                .andReturn();

        String businessJwt = fixture.objectMapper.readTree(callback.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        assertThat(fixture.store.sessions).hasSize(1);
        assertThat(fixture.client.exchangedCode).isEqualTo("code-1");

        fixture.mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + businessJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("User One"))
                .andExpect(jsonPath("$.data.avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.data.openId").doesNotExist())
                .andExpect(jsonPath("$.data.unionId").doesNotExist())
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.tenantKey").doesNotExist());
    }

    @Test
    void shouldRejectCallbackWithInvalidState() throws Exception {
        TestFixture fixture = new TestFixture();

        fixture.mockMvc.perform(post("/api/auth/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"code-1","state":"missing"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        assertThat(fixture.store.sessions).isEmpty();
        assertThat(fixture.client.exchangedCode).isNull();
    }

    @Test
    void shouldReturnUnauthorizedWithoutSessionAndClearCookieOnLogout() throws Exception {
        TestFixture fixture = new TestFixture();
        LarkOAuthLoginSession session = new LarkOAuthLoginSession(
                "user-token",
                java.time.Instant.now().plusSeconds(3600),
                "refresh-token",
                java.time.Instant.now().plusSeconds(7200),
                "Bearer",
                "docs:doc",
                new LarkOAuthUserResponse("ou_123", "on_123", "user_123", "tenant_123", "User One", null)
        );
        fixture.store.set(SESSION_KEY_PREFIX + "session-1", session, Duration.ofHours(1));
        String businessJwt = fixture.jwtService.issueToken("session-1", session.user(), Duration.ofHours(1));

        fixture.mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        fixture.mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + businessJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        assertThat(fixture.store.sessions).doesNotContainKey(SESSION_KEY_PREFIX + "session-1");
    }

    private static final class TestFixture {

        private final InMemoryStore store = new InMemoryStore();
        private final FakeOAuthClient client = new FakeOAuthClient();
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final LarkBusinessJwtService jwtService;
        private final MockMvc mockMvc;

        private TestFixture() {
            LarkOAuthProperties properties = new LarkOAuthProperties();
            LarkAppProperties appProperties = new LarkAppProperties();
            appProperties.setAppId("app_123");
            appProperties.setAppSecret("secret_123");
            properties.setRedirectUri("http://localhost:8078/api/auth/lark/callback");
            properties.setJwtSecret("test-secret-with-enough-length");
            this.jwtService = new LarkBusinessJwtService(properties, objectMapper);
            LarkOAuthService service = new LarkOAuthService(properties, appProperties, client, store, store, jwtService);
            this.mockMvc = MockMvcBuilders
                    .standaloneSetup(new LarkOAuthController(service))
                    .build();
        }
    }

    private static final class FakeOAuthClient implements LarkOAuthClient {

        private String exchangedCode;

        @Override
        public String getAppAccessToken() {
            return "app-access-token";
        }

        @Override
        public LarkOAuthTokenPayload exchangeAuthorizationCode(String appAccessToken, String code) {
            this.exchangedCode = code;
            return tokenPayload("user-access-token");
        }

        @Override
        public LarkOAuthTokenPayload refreshUserAccessToken(String appAccessToken, String refreshToken) {
            return tokenPayload("refreshed-user-access-token");
        }

        private LarkOAuthTokenPayload tokenPayload(String accessToken) {
            return new LarkOAuthTokenPayload(
                    accessToken,
                    3600,
                    "refresh-token",
                    7200,
                    "Bearer",
                    "docs:doc",
                    "ou_123",
                    "on_123",
                    "user_123",
                    "tenant_123",
                    "User One",
                    "https://example.com/avatar.png"
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
