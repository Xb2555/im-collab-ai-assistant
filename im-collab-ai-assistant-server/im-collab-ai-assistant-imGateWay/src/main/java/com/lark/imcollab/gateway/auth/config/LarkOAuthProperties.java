package com.lark.imcollab.gateway.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "imcollab.gateway.auth")
public class LarkOAuthProperties {

    private String appId;

    private String appSecret;

    private String redirectUri;

    private String authorizeUrl = "https://open.feishu.cn/open-apis/authen/v1/authorize";

    private String openApiBaseUrl = "https://open.feishu.cn";

    private Duration sessionTtl = Duration.ofHours(12);

    private Duration jwtTtl = Duration.ofHours(2);

    private Duration stateTtl = Duration.ofMinutes(10);

    private String jwtSecret;

    private String jwtIssuer = "im-collab-ai-assistant";

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

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getAuthorizeUrl() {
        return authorizeUrl;
    }

    public void setAuthorizeUrl(String authorizeUrl) {
        this.authorizeUrl = authorizeUrl;
    }

    public String getOpenApiBaseUrl() {
        return openApiBaseUrl;
    }

    public void setOpenApiBaseUrl(String openApiBaseUrl) {
        this.openApiBaseUrl = openApiBaseUrl;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Duration getJwtTtl() {
        return jwtTtl;
    }

    public void setJwtTtl(Duration jwtTtl) {
        this.jwtTtl = jwtTtl;
    }

    public Duration getStateTtl() {
        return stateTtl;
    }

    public void setStateTtl(Duration stateTtl) {
        this.stateTtl = stateTtl;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getJwtIssuer() {
        return jwtIssuer;
    }

    public void setJwtIssuer(String jwtIssuer) {
        this.jwtIssuer = jwtIssuer;
    }
}
