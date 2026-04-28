package com.lark.imcollab.skills.lark.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imcollab.gateway.lark")
public class LarkBotMessageProperties {

    private String appId = "";

    private String appSecret = "";

    private String openApiBaseUrl = "https://open.feishu.cn";

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String getOpenApiBaseUrl() {
        return openApiBaseUrl;
    }

    public void setOpenApiBaseUrl(String openApiBaseUrl) {
        this.openApiBaseUrl = openApiBaseUrl;
    }
}
