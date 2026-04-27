package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.skills.lark.auth.LarkAdminAuthorizationTool;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfile;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LarkCliProfileResolver {

    private static final Logger log = LoggerFactory.getLogger(LarkCliProfileResolver.class);

    private final LarkCliProperties larkCliProperties;
    private final LarkAdminAuthorizationTool authorizationTool;

    public LarkCliProfileResolver(
            LarkCliProperties larkCliProperties,
            LarkAdminAuthorizationTool authorizationTool
    ) {
        this.larkCliProperties = larkCliProperties;
        this.authorizationTool = authorizationTool;
    }

    public String resolveConfiguredAppProfileName() {
        String appId = normalize(larkCliProperties.getAppId());
        if (appId == null) {
            log.warn("Configured Lark appId is empty, falling back to default profile.");
            log.info("Resolved Lark CLI profile from configured appId: appId=null, profileName=null");
            return null;
        }
        String profileName = authorizationTool.listAuthorizationProfiles().stream()
                .filter(profile -> appId.equals(profile.appId()))
                .map(AdminAuthorizationProfile::name)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(null);
        if (profileName == null) {
            log.warn("No Lark CLI profile matched configured appId, falling back to default profile: appId={}", appId);
        }
        log.info("Resolved Lark CLI profile from configured appId: appId={}, profileName={}", appId, profileName);
        return profileName;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
