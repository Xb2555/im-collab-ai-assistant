package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.skills.lark.auth.LarkAdminAuthorizationTool;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfile;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
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
    private final LarkCliProperties larkCliProperties;
    private final LarkAdminAuthorizationTool authorizationTool;

    public LarkIMListenerStartupRunner(
            LarkIMListenerProperties properties,
            LarkIMListenerService listenerService,
            LarkCliProperties larkCliProperties,
            LarkAdminAuthorizationTool authorizationTool
    ) {
        this.properties = properties;
        this.listenerService = listenerService;
        this.larkCliProperties = larkCliProperties;
        this.authorizationTool = authorizationTool;
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
        String profileName = larkCliProperties.getProfileName();
        if (profileName == null || profileName.isBlank()) {
            return null;
        }
        String normalizedProfileName = profileName.trim();
        if (profileExists(normalizedProfileName)) {
            return normalizedProfileName;
        }
        log.warn("Configured Lark CLI profile does not exist, falling back to active/default profile: profileName={}",
                normalizedProfileName);
        return null;
    }

    private boolean profileExists(String profileName) {
        try {
            return authorizationTool.listAuthorizationProfiles().stream()
                    .map(AdminAuthorizationProfile::name)
                    .anyMatch(profileName::equals);
        } catch (RuntimeException exception) {
            log.warn("Failed to list Lark CLI profiles, falling back to active/default profile.", exception);
            return false;
        }
    }
}
