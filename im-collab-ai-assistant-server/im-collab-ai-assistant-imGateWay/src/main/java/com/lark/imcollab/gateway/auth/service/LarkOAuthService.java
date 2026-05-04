package com.lark.imcollab.gateway.auth.service;

import com.lark.imcollab.gateway.auth.client.LarkOAuthClient;
import com.lark.imcollab.gateway.auth.config.LarkOAuthProperties;
import com.lark.imcollab.gateway.auth.dto.LarkAuthenticatedSession;
import com.lark.imcollab.gateway.auth.dto.LarkAuthTokenResponse;
import com.lark.imcollab.gateway.auth.dto.LarkFrontendUserResponse;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthLoginResult;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthLoginSession;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthTokenPayload;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;
import com.lark.imcollab.gateway.config.LarkAppProperties;
import com.lark.imcollab.store.redis.RedisJsonStore;
import com.lark.imcollab.store.redis.RedisStringStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Service
public class LarkOAuthService {

    private static final Logger log = LoggerFactory.getLogger(LarkOAuthService.class);
    private static final Duration MIN_SESSION_TTL = Duration.ofMinutes(1);
    private static final String STATE_KEY_PREFIX = "imcollab:auth:lark:state:";
    private static final String SESSION_KEY_PREFIX = "imcollab:auth:lark:session:";
    private static final String CLIENT_WEB = "web";
    private static final String CLIENT_DESKTOP = "desktop";

    private final LarkOAuthProperties properties;
    private final LarkAppProperties appProperties;
    private final LarkOAuthClient oauthClient;
    private final RedisStringStore redisStringStore;
    private final RedisJsonStore redisJsonStore;
    private final LarkBusinessJwtService jwtService;
    private final SecureRandom secureRandom = new SecureRandom();

    public LarkOAuthService(
            LarkOAuthProperties properties,
            LarkAppProperties appProperties,
            LarkOAuthClient oauthClient,
            RedisStringStore redisStringStore,
            RedisJsonStore redisJsonStore,
            LarkBusinessJwtService jwtService
    ) {
        this.properties = properties;
        this.appProperties = appProperties;
        this.oauthClient = oauthClient;
        this.redisStringStore = redisStringStore;
        this.redisJsonStore = redisJsonStore;
        this.jwtService = jwtService;
    }

    public LarkOAuthLoginResult startLogin() {
        return startLoginForWebRedirect();
    }

    public LarkOAuthLoginResult startLoginForWebRedirect() {
        return startLoginByClient(CLIENT_WEB);
    }

    public LarkOAuthLoginResult startLoginByClient(String client) {
        String normalizedClient = normalizeClient(client);
        validateRequired(appProperties.getAppId(), "appId");

        String redirectUri = resolveRedirectUriByClient(normalizedClient);
        validateRequired(redirectUri, "redirectUri");

        String state = randomToken();
        redisStringStore.set(stateKey(state), normalizedClient, properties.getStateTtl());

        Optional<String> scope = authorizationScope();
        URI authorizationUri = buildAuthorizationUri(properties.getQrAuthorizeUrl(), "client_id", redirectUri, state, scope);
        log.info("Lark OAuth login init: client={}, authorizeDomain={}, scope={}",
                normalizedClient, properties.getQrAuthorizeUrl(), scope.orElse("<empty>"));
        return new LarkOAuthLoginResult(authorizationUri, state);
    }

    public LarkOAuthLoginResult startLoginForQrEmbed() {
        return startLoginByClient(CLIENT_WEB);
    }

    private URI buildAuthorizationUri(String authorizeUrl, String clientIdParamName, String redirectUri, String state, Optional<String> scope) {
        StringBuilder builder = new StringBuilder(authorizeUrl);
        appendQuery(builder, true, clientIdParamName, appProperties.getAppId());
        appendQuery(builder, false, "response_type", "code");
        appendQuery(builder, false, "redirect_uri", redirectUri);
        appendQuery(builder, false, "state", state);
        scope.ifPresent(value -> appendQuery(builder, false, "scope", value));
        return URI.create(builder.toString());
    }

    private void appendQuery(StringBuilder builder, boolean first, String name, String value) {
        builder.append(first ? '?' : '&')
                .append(urlEncode(name))
                .append('=')
                .append(urlEncode(value));
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    public LarkAuthTokenResponse completeLogin(String code, String state) {
        validateRequired(code, "code");
        validateRequired(appProperties.getAppSecret(), "appSecret");

        String normalizedState = state == null ? null : state.trim();
        String redirectUri = resolveRedirectUriByState(normalizedState);

        if (normalizedState != null && !normalizedState.isBlank() && !consumeState(normalizedState)) {
            throw new IllegalArgumentException("Invalid or expired oauth state");
        }

        String appAccessToken = oauthClient.getAppAccessToken();
        LarkOAuthTokenPayload payload = oauthClient.exchangeAuthorizationCode(appAccessToken, code.trim(), redirectUri);
        log.info("Lark OAuth token exchanged: scope={}, tokenType={}, expiresIn={}, refreshExpiresIn={}",
                payload.scope(), payload.tokenType(), payload.expiresIn(), payload.refreshExpiresIn());
        LarkOAuthUserResponse userInfo = null;
        try {
            userInfo = oauthClient.fetchCurrentUser(payload.accessToken());
        } catch (RuntimeException exception) {
            log.warn("Failed to fetch current user profile from Lark user_info endpoint.", exception);
        }
        LarkOAuthLoginSession session = createSession(payload, userInfo);
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

    public Optional<LarkAuthenticatedSession> resolveAuthenticatedSessionByBusinessToken(String businessToken) {
        return findSessionByBusinessToken(businessToken)
                .map(session -> new LarkAuthenticatedSession(session.accessToken(), session.user()));
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
        return new LarkFrontendUserResponse(user.openId(), user.name(), user.avatarUrl());
    }

    private boolean consumeState(String state) {
        String key = stateKey(state);
        if (!redisStringStore.hasKey(key)) {
            return false;
        }
        redisStringStore.delete(key);
        return true;
    }

    private String resolveRedirectUriByState(String state) {
        if (state == null || state.isBlank()) {
            return properties.getRedirectUri();
        }
        Optional<String> client = redisStringStore.get(stateKey(state.trim()));
        return resolveRedirectUriByClient(client.orElse(CLIENT_WEB));
    }

    private String resolveRedirectUriByClient(String client) {
        String normalizedClient = normalizeClient(client);
        if (CLIENT_DESKTOP.equals(normalizedClient)) {
            return properties.getDesktopRedirectUri();
        }
        return properties.getRedirectUri();
    }

    private String normalizeClient(String client) {
        if (client == null || client.isBlank()) {
            return CLIENT_WEB;
        }
        String normalized = client.trim().toLowerCase();
        return CLIENT_DESKTOP.equals(normalized) ? CLIENT_DESKTOP : CLIENT_WEB;
    }

    private String stateKey(String state) {
        return STATE_KEY_PREFIX + state;
    }

    private Optional<String> authorizationScope() {
        if (properties.getScopes() == null || properties.getScopes().isEmpty()) {
            return Optional.empty();
        }
        String scope = String.join(" ", properties.getScopes().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList());
        if (scope.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(scope);
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
