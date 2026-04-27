package com.lark.imcollab.gateway.auth.service;

import com.lark.imcollab.gateway.auth.client.LarkOAuthClient;
import com.lark.imcollab.gateway.auth.config.LarkOAuthProperties;
import com.lark.imcollab.gateway.auth.dto.LarkAuthTokenResponse;
import com.lark.imcollab.gateway.auth.dto.LarkFrontendUserResponse;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthLoginResult;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthLoginSession;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthTokenPayload;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;
import com.lark.imcollab.store.redis.RedisJsonStore;
import com.lark.imcollab.store.redis.RedisStringStore;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Service
public class LarkOAuthService {

    private static final Duration MIN_SESSION_TTL = Duration.ofMinutes(1);
    private static final String STATE_KEY_PREFIX = "imcollab:auth:lark:state:";
    private static final String SESSION_KEY_PREFIX = "imcollab:auth:lark:session:";

    private final LarkOAuthProperties properties;
    private final LarkOAuthClient oauthClient;
    private final RedisStringStore redisStringStore;
    private final RedisJsonStore redisJsonStore;
    private final LarkBusinessJwtService jwtService;
    private final SecureRandom secureRandom = new SecureRandom();

    public LarkOAuthService(
            LarkOAuthProperties properties,
            LarkOAuthClient oauthClient,
            RedisStringStore redisStringStore,
            RedisJsonStore redisJsonStore,
            LarkBusinessJwtService jwtService
    ) {
        this.properties = properties;
        this.oauthClient = oauthClient;
        this.redisStringStore = redisStringStore;
        this.redisJsonStore = redisJsonStore;
        this.jwtService = jwtService;
    }

    public LarkOAuthLoginResult startLogin() {
        validateRequired(properties.getAppId(), "appId");
        validateRequired(properties.getRedirectUri(), "redirectUri");

        String state = randomToken();
        redisStringStore.set(stateKey(state), "1", properties.getStateTtl());
        URI authorizationUri = UriComponentsBuilder.fromUriString(properties.getAuthorizeUrl())
                .queryParam("app_id", properties.getAppId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("state", state)
                .build()
                .encode()
                .toUri();
        return new LarkOAuthLoginResult(authorizationUri, state);
    }

    public LarkAuthTokenResponse completeLogin(String code, String state) {
        validateRequired(code, "code");
        validateRequired(properties.getAppSecret(), "appSecret");

        if (state != null && !state.isBlank() && !consumeState(state.trim())) {
            throw new IllegalArgumentException("Invalid or expired oauth state");
        }

        String appAccessToken = oauthClient.getAppAccessToken();
        LarkOAuthTokenPayload payload = oauthClient.exchangeAuthorizationCode(appAccessToken, code.trim());
        LarkOAuthLoginSession session = createSession(payload, null);
        String sessionId = randomToken();
        Duration ttl = sessionTtl(payload);
        redisJsonStore.set(sessionKey(sessionId), session, ttl);
        Duration jwtTtl = jwtTtl();
        String token = jwtService.issueToken(sessionId, session.user(), jwtTtl);
        return new LarkAuthTokenResponse(token, "Bearer", jwtTtl.toSeconds(), toFrontendUser(session.user()));
    }

    public Optional<LarkFrontendUserResponse> findCurrentUserByBusinessToken(String businessToken) {
        return findSessionByBusinessToken(businessToken)
                .map(LarkOAuthLoginSession::user)
                .map(this::toFrontendUser);
    }

    public Optional<String> resolveUserAccessTokenByBusinessToken(String businessToken) {
        Optional<LarkOAuthLoginSession> session = findSessionByBusinessToken(businessToken);
        return session.map(LarkOAuthLoginSession::accessToken);
    }

    public void logoutByBusinessToken(String businessToken) {
        jwtService.parseToken(businessToken)
                .ifPresent(claims -> redisJsonStore.delete(sessionKey(claims.sessionId())));
    }

    private Optional<LarkOAuthLoginSession> findSessionByBusinessToken(String businessToken) {
        return jwtService.parseToken(businessToken)
                .flatMap(claims -> findSession(claims.sessionId()));
    }

    private Optional<LarkOAuthLoginSession> findSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Optional<LarkOAuthLoginSession> session = redisJsonStore.get(sessionKey(sessionId.trim()), LarkOAuthLoginSession.class);
        if (session.isEmpty()) {
            return Optional.empty();
        }
        LarkOAuthLoginSession current = session.get();
        Instant now = Instant.now();
        if (current.accessTokenExpiresAt() == null || current.accessTokenExpiresAt().isAfter(now)) {
            return Optional.of(current);
        }
        if (current.refreshToken() == null || current.refreshTokenExpiresAt() == null
                || !current.refreshTokenExpiresAt().isAfter(now)) {
            redisJsonStore.delete(sessionKey(sessionId.trim()));
            return Optional.empty();
        }
        try {
            String appAccessToken = oauthClient.getAppAccessToken();
            LarkOAuthTokenPayload refreshed = oauthClient.refreshUserAccessToken(appAccessToken, current.refreshToken());
            LarkOAuthLoginSession refreshedSession = createSession(refreshed, current.user());
            redisJsonStore.set(sessionKey(sessionId.trim()), refreshedSession, sessionTtl(refreshed));
            return Optional.of(refreshedSession);
        } catch (RuntimeException exception) {
            redisJsonStore.delete(sessionKey(sessionId.trim()));
            return Optional.empty();
        }
    }

    private LarkOAuthLoginSession createSession(LarkOAuthTokenPayload payload, LarkOAuthUserResponse fallbackUser) {
        validateRequired(payload.accessToken(), "accessToken");
        Instant now = Instant.now();
        LarkOAuthUserResponse user = new LarkOAuthUserResponse(
                firstNonBlank(payload.openId(), fallbackUser == null ? null : fallbackUser.openId()),
                firstNonBlank(payload.unionId(), fallbackUser == null ? null : fallbackUser.unionId()),
                firstNonBlank(payload.userId(), fallbackUser == null ? null : fallbackUser.userId()),
                firstNonBlank(payload.tenantKey(), fallbackUser == null ? null : fallbackUser.tenantKey()),
                firstNonBlank(payload.name(), fallbackUser == null ? null : fallbackUser.name()),
                firstNonBlank(payload.avatarUrl(), fallbackUser == null ? null : fallbackUser.avatarUrl())
        );
        return new LarkOAuthLoginSession(
                payload.accessToken(),
                expiresAt(now, payload.expiresIn()),
                payload.refreshToken(),
                expiresAt(now, payload.refreshExpiresIn()),
                payload.tokenType(),
                payload.scope(),
                user
        );
    }

    private Duration sessionTtl(LarkOAuthTokenPayload payload) {
        Duration configuredTtl = properties.getSessionTtl() == null ? Duration.ofHours(12) : properties.getSessionTtl();
        long tokenSeconds = payload.refreshExpiresIn() > 0 ? payload.refreshExpiresIn() : payload.expiresIn();
        Duration tokenTtl = tokenSeconds > 0 ? Duration.ofSeconds(tokenSeconds) : configuredTtl;
        Duration ttl = configuredTtl.compareTo(tokenTtl) <= 0 ? configuredTtl : tokenTtl;
        return ttl.compareTo(MIN_SESSION_TTL) < 0 ? MIN_SESSION_TTL : ttl;
    }

    private Duration jwtTtl() {
        Duration ttl = properties.getJwtTtl() == null ? Duration.ofHours(2) : properties.getJwtTtl();
        return ttl.compareTo(MIN_SESSION_TTL) < 0 ? MIN_SESSION_TTL : ttl;
    }

    private Instant expiresAt(Instant now, long expiresInSeconds) {
        if (expiresInSeconds <= 0) {
            return null;
        }
        return now.plusSeconds(expiresInSeconds);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private LarkFrontendUserResponse toFrontendUser(LarkOAuthUserResponse user) {
        return new LarkFrontendUserResponse(user.name(), user.avatarUrl());
    }

    private boolean consumeState(String state) {
        String key = stateKey(state);
        if (!redisStringStore.hasKey(key)) {
            return false;
        }
        redisStringStore.delete(key);
        return true;
    }

    private String stateKey(String state) {
        return STATE_KEY_PREFIX + state;
    }

    private String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
