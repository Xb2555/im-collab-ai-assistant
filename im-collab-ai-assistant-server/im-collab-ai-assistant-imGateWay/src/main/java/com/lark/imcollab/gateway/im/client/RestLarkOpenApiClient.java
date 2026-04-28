package com.lark.imcollab.gateway.im.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.lark.imcollab.gateway.config.LarkAppProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.util.Map;

@Component
public class RestLarkOpenApiClient implements LarkOpenApiClient {

    private final LarkAppProperties appProperties;
    private final RestClient restClient;

    public RestLarkOpenApiClient(LarkAppProperties appProperties, RestClient.Builder restClientBuilder) {
        this.appProperties = appProperties;
        this.restClient = restClientBuilder
                .baseUrl(appProperties.getOpenApiBaseUrl())
                .build();
    }

    @Override
    public JsonNode get(String path, Map<String, String> queryParams, String accessToken) {
        JsonNode response = restClient.get()
                .uri(uriBuilder -> buildUri(uriBuilder, path, queryParams))
                .headers(headers -> headers.setBearerAuth(requireValue(accessToken, "accessToken")))
                .retrieve()
                .body(JsonNode.class);
        return requireSuccess(response);
    }

    @Override
    public JsonNode getWithTenantToken(String path, Map<String, String> queryParams) {
        return get(path, queryParams, getTenantAccessToken());
    }

    @Override
    public JsonNode post(String path, Map<String, String> queryParams, Object body, String accessToken) {
        JsonNode response = restClient.post()
                .uri(uriBuilder -> buildUri(uriBuilder, path, queryParams))
                .headers(headers -> headers.setBearerAuth(requireValue(accessToken, "accessToken")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        return requireSuccess(response);
    }

    @Override
    public JsonNode postWithTenantToken(String path, Map<String, String> queryParams, Object body) {
        return post(path, queryParams, body, getTenantAccessToken());
    }

    private String getTenantAccessToken() {
        JsonNode response = restClient.post()
                .uri("/open-apis/auth/v3/tenant_access_token/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "app_id", requireValue(appProperties.getAppId(), "appId"),
                        "app_secret", requireValue(appProperties.getAppSecret(), "appSecret")
                ))
                .retrieve()
                .body(JsonNode.class);
        return requireSuccess(response).path("tenant_access_token").asText();
    }

    private java.net.URI buildUri(UriBuilder uriBuilder, String path, Map<String, String> queryParams) {
        UriBuilder builder = uriBuilder.path(path);
        if (queryParams != null) {
            queryParams.forEach((key, value) -> {
                if (value != null && !value.isBlank()) {
                    builder.queryParam(key, value);
                }
            });
        }
        return builder.build();
    }

    private JsonNode requireSuccess(JsonNode response) {
        if (response == null) {
            throw new LarkOpenApiException(-1, "Empty lark openapi response");
        }
        int code = response.path("code").asInt(-1);
        if (code != 0) {
            throw new LarkOpenApiException(code, response.path("msg").asText("lark openapi request failed"));
        }
        JsonNode data = response.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return response;
        }
        return data;
    }

    private String requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
        return value.trim();
    }
}
