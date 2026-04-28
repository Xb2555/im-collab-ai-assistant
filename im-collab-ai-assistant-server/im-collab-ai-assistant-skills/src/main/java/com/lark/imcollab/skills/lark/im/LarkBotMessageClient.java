package com.lark.imcollab.skills.lark.im;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.lark.config.LarkBotMessageProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class LarkBotMessageClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final LarkBotMessageProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LarkBotMessageClient(LarkBotMessageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
    }

    public LarkBotMessageResult sendTextToOpenId(String openId, String text) {
        String normalizedOpenId = requireValue(openId, "openId");
        String normalizedText = requireValue(text, "text");

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("receive_id", normalizedOpenId);
        requestBody.put("msg_type", "text");
        requestBody.put("content", serialize(Map.of("text", normalizedText)));
        requestBody.put("uuid", UUID.randomUUID().toString());

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
        String normalizedMessageId = requireValue(messageId, "messageId");
        String normalizedText = requireValue(text, "text");

        JsonNode data = post(
                "/open-apis/im/v1/messages/" + normalizedMessageId + "/reply",
                Map.of(
                        "content", serialize(Map.of("text", normalizedText)),
                        "msg_type", "text",
                        "uuid", UUID.randomUUID().toString()
                ),
                getTenantAccessToken()
        );
        return new LarkBotMessageResult(
                optionalText(data, "message_id"),
                optionalText(data, "chat_id"),
                optionalText(data, "create_time")
        );
    }

    private String getTenantAccessToken() {
        JsonNode data = post(
                "/open-apis/auth/v3/tenant_access_token/internal",
                Map.of(
                        "app_id", requireValue(properties.getAppId(), "appId"),
                        "app_secret", requireValue(properties.getAppSecret(), "appSecret")
                ),
                null
        );
        return requireValue(optionalText(data, "tenant_access_token"), "tenantAccessToken");
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

        HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to call Lark OpenAPI", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lark OpenAPI call interrupted", exception);
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

    private String requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
        return value.trim();
    }
}
