package com.lark.imcollab.gateway.im.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.lark.imcollab.gateway.im.client.LarkOpenApiException;
import com.lark.imcollab.gateway.im.client.LarkOpenApiClient;
import com.lark.imcollab.store.redis.RedisJsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Service
public class LarkUserProfileHydrationService {

    private static final Logger log = LoggerFactory.getLogger(LarkUserProfileHydrationService.class);
    private static final Duration PROFILE_CACHE_TTL = Duration.ofHours(6);
    private static final Duration DENIED_PROFILE_CACHE_TTL = Duration.ofMinutes(15);
    private static final String PROFILE_CACHE_KEY_PREFIX = "imcollab:lark:user-profile:";
    private static final String PROFILE_DENIED_CACHE_KEY_PREFIX = "imcollab:lark:user-profile-denied:";
    private static final String AUTH_MODE_USER = "user";
    private static final String AUTH_MODE_TENANT = "tenant";
    private static final int LARK_NO_USER_AUTHORITY_ERROR = 41050;

    private final LarkOpenApiClient openApiClient;
    private final RedisJsonStore redisJsonStore;

    public LarkUserProfileHydrationService(LarkOpenApiClient openApiClient, RedisJsonStore redisJsonStore) {
        this.openApiClient = openApiClient;
        this.redisJsonStore = redisJsonStore;
    }

    public LarkUserProfile resolveByUserAccessToken(String userAccessToken, String openId) {
        return resolve(openId, AUTH_MODE_USER, () -> openApiClient.get(userPath(openId), queryParams(), userAccessToken));
    }

    public LarkUserProfile resolveByTenantAccessToken(String openId) {
        return resolve(openId, AUTH_MODE_TENANT, () -> openApiClient.getWithTenantToken(userPath(openId), queryParams()));
    }

    private LarkUserProfile resolve(String openId, String authMode, ProfileFetcher fetcher) {
        String normalizedOpenId = normalize(openId);
        if (normalizedOpenId == null) {
            return null;
        }
        if (isHydrationDenied(normalizedOpenId, authMode)) {
            return new LarkUserProfile(normalizedOpenId, null, null);
        }
        Optional<LarkUserProfile> cached = readCachedProfile(normalizedOpenId);
        if (cached.isPresent()) {
            return cached.get();
        }
        try {
            LarkUserProfile profile = parseProfile(normalizedOpenId, fetcher.fetch());
            if (hasDisplayData(profile)) {
                writeCachedProfile(normalizedOpenId, profile);
            }
            return profile;
        } catch (LarkOpenApiException exception) {
            if (exception.getLarkCode() == LARK_NO_USER_AUTHORITY_ERROR) {
                cacheHydrationDenied(normalizedOpenId, authMode);
                log.debug("Skipped Lark user profile hydration due to missing authority: openId={}, authMode={}",
                        normalizedOpenId, authMode);
            } else {
                log.warn("Failed to hydrate Lark user profile: openId={}, authMode={}, larkCode={}, message={}",
                        normalizedOpenId, authMode, exception.getLarkCode(), exception.getMessage());
            }
            return new LarkUserProfile(normalizedOpenId, null, null);
        } catch (RuntimeException exception) {
            log.warn("Failed to hydrate Lark user profile: openId={}, authMode={}, message={}",
                    normalizedOpenId, authMode, exception.getMessage());
            return new LarkUserProfile(normalizedOpenId, null, null);
        }
    }

    private Optional<LarkUserProfile> readCachedProfile(String openId) {
        try {
            Optional<LarkUserProfile> cached = redisJsonStore.get(cacheKey(openId), LarkUserProfile.class);
            if (cached.isPresent() && !hasDisplayData(cached.get())) {
                redisJsonStore.delete(cacheKey(openId));
                return Optional.empty();
            }
            return cached;
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private void writeCachedProfile(String openId, LarkUserProfile profile) {
        try {
            redisJsonStore.set(cacheKey(openId), profile, PROFILE_CACHE_TTL);
        } catch (RuntimeException ignored) {
            // Profile hydration is a display enhancement; cache failures must not block IM messages.
        }
    }

    private void cacheHydrationDenied(String openId, String authMode) {
        try {
            redisJsonStore.set(deniedCacheKey(openId, authMode), Boolean.TRUE, DENIED_PROFILE_CACHE_TTL);
        } catch (RuntimeException ignored) {
            // Denied-cache failures must not block IM messages.
        }
    }

    private boolean isHydrationDenied(String openId, String authMode) {
        try {
            return redisJsonStore.get(deniedCacheKey(openId, authMode), Boolean.class).orElse(false);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean hasDisplayData(LarkUserProfile profile) {
        return profile != null
                && ((profile.name() != null && !profile.name().isBlank())
                || (profile.avatarUrl() != null && !profile.avatarUrl().isBlank()));
    }

    private LarkUserProfile parseProfile(String openId, JsonNode data) {
        JsonNode user = data == null ? null : data.path("user");
        JsonNode source = user == null || user.isMissingNode() || user.isNull() ? data : user;
        if (source == null) {
            return new LarkUserProfile(openId, null, null);
        }
        String resolvedOpenId = firstText(text(source, "open_id"), openId);
        String name = firstText(
                text(source, "name"),
                text(source, "localized_name"),
                text(source, "display_name"),
                text(source, "en_name")
        );
        String avatarUrl = firstText(
                text(source, "avatar_url"),
                text(source.path("avatar"), "avatar_72"),
                text(source.path("avatar"), "avatar_240"),
                text(source.path("avatar"), "avatar_640"),
                text(source.path("avatar"), "avatar_origin")
        );
        return new LarkUserProfile(resolvedOpenId, name, avatarUrl);
    }

    private String userPath(String openId) {
        return "/open-apis/contact/v3/users/" + openId;
    }

    private Map<String, String> queryParams() {
        return Map.of("user_id_type", "open_id");
    }

    private String cacheKey(String openId) {
        return PROFILE_CACHE_KEY_PREFIX + openId;
    }

    private String deniedCacheKey(String openId, String authMode) {
        return PROFILE_DENIED_CACHE_KEY_PREFIX + authMode + ":" + openId;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (!isHydratableOpenId(normalized)) {
            return null;
        }
        return normalized;
    }

    private boolean isHydratableOpenId(String value) {
        return value != null
                && value.startsWith("ou_")
                && !"bot".equalsIgnoreCase(value);
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull() || field.asText().isBlank()) {
            return null;
        }
        return field.asText();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface ProfileFetcher {
        JsonNode fetch();
    }
}
