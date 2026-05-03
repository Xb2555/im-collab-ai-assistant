package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.lark.config.LarkBotMessageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
public class LarkDocOpenApiClient {

    private static final long TOKEN_REFRESH_SKEW_SECONDS = 60L;
    private static final long FALLBACK_TOKEN_TTL_SECONDS = 300L;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final LarkBotMessageProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Object tenantAccessTokenLock = new Object();

    private volatile String cachedTenantAccessToken;
    private volatile long tenantAccessTokenExpiresAtEpochMilli;

    public LarkDocOpenApiClient(LarkBotMessageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public JsonNode post(String pathWithQuery, Object body, int timeoutSeconds) {
        return post(pathWithQuery, body, timeoutSeconds, true);
    }

    private JsonNode post(String pathWithQuery, Object body, int timeoutSeconds, boolean withTenantToken) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(buildUri(pathWithQuery))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(serialize(body), StandardCharsets.UTF_8));
        if (withTenantToken) {
            requestBuilder.header("Authorization", "Bearer " + getTenantAccessToken());
        }
        return execute(requestBuilder, pathWithQuery, 1);
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
                    20,
                    false
            );
            String refreshedToken = requireValue(optionalText(data, "tenant_access_token"), "tenantAccessToken");
            long expireSeconds = data.path("expire").asLong(FALLBACK_TOKEN_TTL_SECONDS);
            long effectiveTtlSeconds = Math.max(1L, expireSeconds - TOKEN_REFRESH_SKEW_SECONDS);
            cachedTenantAccessToken = refreshedToken;
            tenantAccessTokenExpiresAtEpochMilli = System.currentTimeMillis() + Duration.ofSeconds(effectiveTtlSeconds).toMillis();
            log.debug("Refreshed Lark doc tenant access token, expiresInSeconds={}", expireSeconds);
            return refreshedToken;
        }
    }

    private JsonNode execute(HttpRequest.Builder requestBuilder, String pathWithQuery, int attempt) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            if (attempt < MAX_RETRY_ATTEMPTS) {
                sleepBeforeRetry(attempt);
                return execute(requestBuilder, pathWithQuery, attempt + 1);
            }
            throw new IllegalStateException("飞书文档 OpenAPI 调用失败，请检查网络或应用凭证。", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("飞书文档 OpenAPI 调用被中断。", exception);
        }

        JsonNode root = parse(response.body());
        int code = root.path("code").asInt(-1);
        if ((response.statusCode() == 400 && code == 99991400) && attempt < MAX_RETRY_ATTEMPTS) {
            sleepBeforeRetry(attempt);
            return execute(requestBuilder, pathWithQuery, attempt + 1);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("飞书文档 OpenAPI HTTP 调用失败: status="
                    + response.statusCode() + ", path=" + pathWithQuery + ", body=" + compact(response.body()));
        }
        if (code != 0) {
            throw new IllegalStateException("飞书文档 OpenAPI 调用失败: code="
                    + code + ", msg=" + root.path("msg").asText("unknown"));
        }
        JsonNode data = root.path("data");
        return data.isMissingNode() || data.isNull() ? root : data;
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

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize Lark doc OpenAPI request body", exception);
        }
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse Lark doc OpenAPI response", exception);
        }
    }

    private String optionalText(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull() || field.asText().isBlank()) {
            return null;
        }
        return field.asText();
    }

    private String requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
        return value.trim();
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(400L * (1L << Math.max(0, attempt - 1)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("飞书文档 OpenAPI 重试被中断。", exception);
        }
    }

    private String compact(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }
}
