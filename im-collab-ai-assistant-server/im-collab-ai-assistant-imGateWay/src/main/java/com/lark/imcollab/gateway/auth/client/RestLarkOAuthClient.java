package com.lark.imcollab.gateway.auth.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.lark.imcollab.gateway.auth.config.LarkOAuthProperties;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthTokenPayload;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;
import com.lark.imcollab.gateway.config.LarkAppProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class RestLarkOAuthClient implements LarkOAuthClient {

    private final LarkAppProperties appProperties;
    private final LarkOAuthProperties oauthProperties;
    private final RestClient restClient;

    public RestLarkOAuthClient(
            LarkAppProperties appProperties,
            LarkOAuthProperties oauthProperties,
            RestClient.Builder restClientBuilder
    ) {
        this.appProperties = appProperties;
        this.oauthProperties = oauthProperties;
        this.restClient = restClientBuilder
                .baseUrl(appProperties.getOpenApiBaseUrl())
                .build();
    }

    @Override
    public String getAppAccessToken() {
        JsonNode response = restClient.post()
                .uri("/open-apis/auth/v3/app_access_token/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "app_id", appProperties.getAppId(),
                        "app_secret", appProperties.getAppSecret()
                ))
                .retrieve()
                .body(JsonNode.class);
        return requireSuccess(response).path("app_access_token").asText();
    }

    @Override
    public LarkOAuthTokenPayload exchangeAuthorizationCode(String appAccessToken, String code) {
        JsonNode response = restClient.post()
                .uri("/open-apis/authen/v2/oauth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "grant_type", "authorization_code",
                        "client_id", appProperties.getAppId(),
                        "client_secret", appProperties.getAppSecret(),
                        "code", code,
                        "redirect_uri", oauthProperties.getRedirectUri()
                ))
                .retrieve()
                .body(JsonNode.class);
        return readTokenPayload(requireSuccess(response));
    }

    @Override
    public LarkOAuthTokenPayload refreshUserAccessToken(String appAccessToken, String refreshToken) {
        JsonNode response = restClient.post()
                .uri("/open-apis/authen/v2/oauth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "grant_type", "refresh_token",
                        "client_id", appProperties.getAppId(),
                        "client_secret", appProperties.getAppSecret(),
                        "refresh_token", refreshToken
                ))
                .retrieve()
                .body(JsonNode.class);
        return readTokenPayload(requireSuccess(response));
    }

    @Override
    public LarkOAuthUserResponse fetchCurrentUser(String userAccessToken) {
        JsonNode response = restClient.get()
                .uri("/open-apis/authen/v1/user_info")
                .headers(headers -> headers.setBearerAuth(userAccessToken))
                .retrieve()
                .body(JsonNode.class);
        JsonNode data = requireSuccess(response);
        return new LarkOAuthUserResponse(
                optionalText(data, "open_id"),
                optionalText(data, "union_id"),
                optionalText(data, "user_id"),
                optionalText(data, "tenant_key"),
                optionalText(data, "name"),
                optionalText(data, "avatar_url")
        );
    }

    private JsonNode requireSuccess(JsonNode response) {
        if (response == null) {
            throw new IllegalStateException("Empty lark oauth response");
        }
        int code = response.path("code").asInt(-1);
        if (code != 0) {
            String message = response.path("msg").asText("lark oauth request failed");
            throw new IllegalStateException(message);
        }
        JsonNode data = response.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return response;
        }
        return data;
    }

    private LarkOAuthTokenPayload readTokenPayload(JsonNode data) {
        String accessToken = requireText(data, "access_token");
        return new LarkOAuthTokenPayload(
                accessToken,
                data.path("expires_in").asLong(0),
                optionalText(data, "refresh_token"),
                data.path("refresh_expires_in").asLong(0),
                optionalText(data, "token_type"),
                optionalText(data, "scope"),
                optionalText(data, "open_id"),
                optionalText(data, "union_id"),
                optionalText(data, "user_id"),
                optionalText(data, "tenant_key"),
                optionalText(data, "name"),
                optionalText(data, "avatar_url")
        );
    }

    private String requireText(JsonNode root, String fieldName) {
        String value = optionalText(root, fieldName);
        if (value == null) {
            throw new IllegalStateException("Lark oauth response missing field: " + fieldName);
        }
        return value;
    }

    private String optionalText(JsonNode root, String fieldName) {
        JsonNode field = root.path(fieldName);
        if (field.isMissingNode() || field.isNull() || field.asText().isBlank()) {
            return null;
        }
        return field.asText();
    }
}
