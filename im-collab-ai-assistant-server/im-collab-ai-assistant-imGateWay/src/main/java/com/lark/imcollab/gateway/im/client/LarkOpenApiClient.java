package com.lark.imcollab.gateway.im.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public interface LarkOpenApiClient {

    JsonNode get(String path, Map<String, String> queryParams, String accessToken);

    JsonNode getWithTenantToken(String path, Map<String, String> queryParams);

    JsonNode post(String path, Map<String, String> queryParams, Object body, String accessToken);

    JsonNode postWithTenantToken(String path, Map<String, String> queryParams, Object body);
}
