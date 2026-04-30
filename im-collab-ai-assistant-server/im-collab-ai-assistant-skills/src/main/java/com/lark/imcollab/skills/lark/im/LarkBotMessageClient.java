package com.lark.imcollab.skills.lark.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.lark.config.LarkBotMessageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class LarkBotMessageClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
    private static final Logger log = LoggerFactory.getLogger(LarkBotMessageClient.class);
    private static final long TOKEN_REFRESH_SKEW_SECONDS = 60L;
    private static final long FALLBACK_TOKEN_TTL_SECONDS = 300L;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 1_500L;
    static final int MAX_UUID_LENGTH = 50;

    private final LarkBotMessageProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Object tenantAccessTokenLock = new Object();

    private volatile String cachedTenantAccessToken;
    private volatile long tenantAccessTokenExpiresAtEpochMilli;

    public LarkBotMessageClient(LarkBotMessageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
    }

    public LarkBotMessageResult sendTextToOpenId(String openId, String text) {
        return sendTextToOpenId(openId, text, UUID.randomUUID().toString());
    }

    public LarkBotMessageResult sendTextToOpenId(String openId, String text, String idempotencyKey) {
        String normalizedOpenId = requireValue(openId, "openId");
        String normalizedText = requireValue(text, "text");
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("receive_id", normalizedOpenId);
        requestBody.put("msg_type", "text");
        requestBody.put("content", serialize(Map.of("text", normalizedText)));
        requestBody.put("uuid", normalizedIdempotencyKey);

        JsonNode data = post(
                "/open-apis/im/v1/messages?receive_id_type=open_id",
                requestBody,
                getTenantAccessToken()
        );
        return new LarkBotMessageResult(
                optionalText(data, "message_id"),
                optionalText(data, "chat_id"),
                optionalText(data, "create_time")
        );
    }

    public LarkBotMessageResult replyText(String messageId, String text) {
        return replyText(messageId, text, UUID.randomUUID().toString());
    }

    public LarkBotMessageResult replyText(String messageId, String text, String idempotencyKey) {
        String normalizedMessageId = requireValue(messageId, "messageId");
        String normalizedText = requireValue(text, "text");
        String normalizedIdempotencyKey = normalizeIdempotencyKey(idempotencyKey);

        JsonNode data = post(
                "/open-apis/im/v1/messages/" + normalizedMessageId + "/reply",
                Map.of(
                        "content", serialize(Map.of("text", normalizedText)),
                        "msg_type", "text",
                        "uuid", normalizedIdempotencyKey
                ),
                getTenantAccessToken()
        );
        return new LarkBotMessageResult(
                optionalText(data, "message_id"),
                optionalText(data, "chat_id"),
                optionalText(data, "create_time")
        );
    }

    public LarkMessageHistoryResponse fetchHistory(
            String containerIdType,
            String containerId,
            String startTime,
            String endTime,
            String sortType,
            Integer pageSize,
            String pageToken,
            String cardMsgContentType
    ) {
        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("container_id_type", normalizeContainerIdType(containerIdType));
        queryParams.put("container_id", requireValue(containerId, "containerId"));
        putIfPresent(queryParams, "start_time", startTime);
        putIfPresent(queryParams, "end_time", endTime);
        putIfPresent(queryParams, "sort_type", sortType);
        putIfPresent(queryParams, "page_size", pageSize == null ? null : String.valueOf(normalizePageSize(pageSize)));
        putIfPresent(queryParams, "page_token", pageToken);
        putIfPresent(queryParams, "card_msg_content_type", cardMsgContentType);

        JsonNode data = get(
                "/open-apis/im/v1/messages" + toQueryString(queryParams),
                getTenantAccessToken()
        );
        return LarkMessageHistoryMapper.fromData(data);
    }

    private String getTenantAccessToken() {
        String cachedToken = cachedTenantAccessToken;
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tenantAccessTokenExpiresAtEpochMilli) {
            return cachedToken;
        }

        synchronized (tenantAccessTokenLock) {
            cachedToken = cachedTenantAccessToken;
            now = System.currentTimeMillis();
            if (cachedToken != null && now < tenantAccessTokenExpiresAtEpochMilli) {
                return cachedToken;
            }

            JsonNode data = post(
                    "/open-apis/auth/v3/tenant_access_token/internal",
                    Map.of(
                            "app_id", requireValue(properties.getAppId(), "appId"),
                            "app_secret", requireValue(properties.getAppSecret(), "appSecret")
                    ),
                    null
            );
            String refreshedToken = requireValue(optionalText(data, "tenant_access_token"), "tenantAccessToken");
            long expireSeconds = data.path("expire").asLong(FALLBACK_TOKEN_TTL_SECONDS);
            long effectiveTtlSeconds = Math.max(1L, expireSeconds - TOKEN_REFRESH_SKEW_SECONDS);
            cachedTenantAccessToken = refreshedToken;
            tenantAccessTokenExpiresAtEpochMilli = System.currentTimeMillis() + Duration.ofSeconds(effectiveTtlSeconds).toMillis();
            log.debug("Refreshed Lark tenant access token, expiresInSeconds={}", expireSeconds);
            return refreshedToken;
        }
    }

    private JsonNode get(String pathWithQuery, String accessToken) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(buildUri(pathWithQuery))
                .timeout(DEFAULT_TIMEOUT)
                .GET();
        if (accessToken != null && !accessToken.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + accessToken.trim());
        }
        return execute(requestBuilder, pathWithQuery);
    }

    private JsonNode post(String pathWithQuery, Object body, String accessToken) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(buildUri(pathWithQuery))
                .timeout(DEFAULT_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(serialize(body)));
        if (accessToken != null && !accessToken.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + accessToken.trim());
        }
        return execute(requestBuilder, pathWithQuery);
    }

    private JsonNode execute(HttpRequest.Builder requestBuilder, String pathWithQuery) {
        HttpResponse<String> response = null;
        IOException lastIoException = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                break;
            } catch (IOException exception) {
                lastIoException = exception;
                if (attempt >= MAX_RETRY_ATTEMPTS) {
                    break;
                }
                log.warn("Lark OpenAPI call failed, retrying: path={}, attempt={}/{}",
                        pathWithQuery, attempt, MAX_RETRY_ATTEMPTS, exception);
                sleepBeforeRetry();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Lark OpenAPI call interrupted", exception);
            }
        }
        if (response == null) {
            throw new IllegalStateException("Failed to call Lark OpenAPI", lastIoException);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "Lark OpenAPI HTTP request failed: status=" + response.statusCode() + ", body=" + response.body()
            );
        }

        JsonNode root = parse(response.body());
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException(
                    "Lark OpenAPI request failed: code=" + code + ", msg=" + root.path("msg").asText("unknown error")
            );
        }

        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return root;
        }
        return data;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lark OpenAPI retry interrupted", exception);
        }
    }

    private URI buildUri(String pathWithQuery) {
        String baseUrl = properties.getOpenApiBaseUrl();
        String normalizedBaseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "https://open.feishu.cn"
                : baseUrl.trim();
        if (normalizedBaseUrl.endsWith("/") && pathWithQuery.startsWith("/")) {
            return URI.create(normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1) + pathWithQuery);
        }
        if (!normalizedBaseUrl.endsWith("/") && !pathWithQuery.startsWith("/")) {
            return URI.create(normalizedBaseUrl + "/" + pathWithQuery);
        }
        return URI.create(normalizedBaseUrl + pathWithQuery);
    }

    private String toQueryString(Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        queryParams.forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                return;
            }
            if (builder.isEmpty()) {
                builder.append('?');
            } else {
                builder.append('&');
            }
            builder.append(encode(key)).append('=').append(encode(value));
        });
        return builder.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize Lark OpenAPI request body", exception);
        }
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse Lark OpenAPI response", exception);
        }
    }

    private String optionalText(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull() || field.asText().isBlank()) {
            return null;
        }
        return field.asText();
    }

    private String normalizeContainerIdType(String containerIdType) {
        String normalized = requireValue(containerIdType, "containerIdType");
        if (!"chat".equals(normalized) && !"thread".equals(normalized)) {
            throw new IllegalArgumentException("containerIdType must be chat or thread");
        }
        return normalized;
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > 50) {
            throw new IllegalArgumentException("pageSize must be between 1 and 50");
        }
        return pageSize;
    }

    private void putIfPresent(Map<String, String> queryParams, String key, String value) {
        if (value != null && !value.isBlank()) {
            queryParams.put(key, value.trim());
        }
    }

    private String requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
        return value.trim();
    }

    static String normalizeIdempotencyKey(String idempotencyKey) {
        String normalized = requireStaticValue(idempotencyKey, "idempotencyKey");
        if (normalized.length() <= MAX_UUID_LENGTH) {
            return normalized;
        }
        return "im-" + sha256Hex(normalized).substring(0, MAX_UUID_LENGTH - 3);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    private static String requireStaticValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
        return value.trim();
    }
}
