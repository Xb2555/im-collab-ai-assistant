package com.lark.imcollab.gateway.im.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class LarkIMListenerStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LarkIMListenerStartupRunner.class);

    private final LarkIMListenerProperties properties;
    private final LarkIMListenerService listenerService;

    public LarkIMListenerStartupRunner(
            LarkIMListenerProperties properties,
            LarkIMListenerService listenerService
    ) {
        this.properties = properties;
        this.listenerService = listenerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isAutoStartEnabled()) {
            log.info("Scenario A Lark IM listener auto-start is disabled.");
            return;
        }
        try {
            LarkIMListenerStatusResponse status = listenerService.startDefault(defaultProfileName());
            log.info("Scenario A Lark IM listener auto-started: profileName={}, state={}",
                    status.profileName(), status.state());
        } catch (RuntimeException exception) {
            log.warn("Failed to auto-start Scenario A Lark IM listener.", exception);
        }
    }

    private String defaultProfileName() {
        String profileName = properties.getDefaultProfileName();
        if (profileName == null || profileName.isBlank()) {
            return null;
        }
        return profileName.trim();
    }
}
