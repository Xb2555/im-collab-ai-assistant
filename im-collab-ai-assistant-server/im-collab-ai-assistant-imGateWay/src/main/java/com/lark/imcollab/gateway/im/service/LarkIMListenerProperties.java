package com.lark.imcollab.gateway.im.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imcollab.gateway.im.listener")
public class LarkIMListenerProperties {

    private boolean autoStartEnabled = true;
    private boolean suppressStartupReplayEnabled = true;
    private long startupReplayGracePeriodMillis = 10_000L;

    public boolean isAutoStartEnabled() {
        return autoStartEnabled;
    }

    public void setAutoStartEnabled(boolean autoStartEnabled) {
        this.autoStartEnabled = autoStartEnabled;
    }

    public boolean isSuppressStartupReplayEnabled() {
        return suppressStartupReplayEnabled;
    }

    public void setSuppressStartupReplayEnabled(boolean suppressStartupReplayEnabled) {
        this.suppressStartupReplayEnabled = suppressStartupReplayEnabled;
    }

    public long getStartupReplayGracePeriodMillis() {
        return startupReplayGracePeriodMillis;
    }

    public void setStartupReplayGracePeriodMillis(long startupReplayGracePeriodMillis) {
        this.startupReplayGracePeriodMillis = startupReplayGracePeriodMillis;
    }
}
