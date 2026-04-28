package com.lark.imcollab.gateway.im.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imcollab.gateway.im.listener")
public class LarkIMListenerProperties {

    private boolean autoStartEnabled = true;

    public boolean isAutoStartEnabled() {
        return autoStartEnabled;
    }

    public void setAutoStartEnabled(boolean autoStartEnabled) {
        this.autoStartEnabled = autoStartEnabled;
    }
}
