package com.lark.imcollab.gateway.im.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imcollab.gateway.im.event")
public class LarkIMEventProperties {

    private String appId = "";
    private String appSecret = "";
    private String domain = "";
    private boolean autoReconnectEnabled = true;

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

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public boolean isAutoReconnectEnabled() {
        return autoReconnectEnabled;
    }

    public void setAutoReconnectEnabled(boolean autoReconnectEnabled) {
        this.autoReconnectEnabled = autoReconnectEnabled;
    }
}
