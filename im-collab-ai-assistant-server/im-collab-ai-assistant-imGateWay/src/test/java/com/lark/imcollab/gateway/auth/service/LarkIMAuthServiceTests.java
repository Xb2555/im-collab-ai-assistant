package com.lark.imcollab.gateway.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationInfoResponse;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationStartResponse;
import com.lark.imcollab.gateway.im.service.LarkCliProfileResolver;
import com.lark.imcollab.skills.lark.auth.LarkAdminAuthorizationTool;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationSession;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LarkIMAuthServiceTests {

    @Test
    void shouldStartAuthorizationWithResolvedProfile() {
        StubLarkAdminAuthorizationTool tool = new StubLarkAdminAuthorizationTool("""
                {"event":"authorization_complete","user_open_id":"ou_123","user_name":"用户992704"}
                """);
        LarkIMAuthService service = new LarkIMAuthService(tool, new ObjectMapper(), new StubProfileResolver("profile-123"));

        LarkAdminAuthorizationStartResponse response = service.startLarkAdminAuthorization();

        assertThat(tool.startedProfileName).isEqualTo("profile-123");
        assertThat(response.deviceCode()).isEqualTo("device-456");
        assertThat(response.qrCodePngBase64()).isEqualTo("AQID");
    }

    @Test
    void shouldCompleteAuthorizationWithResolvedProfile() {
        StubLarkAdminAuthorizationTool tool = new StubLarkAdminAuthorizationTool("""
                {
                  "event": "authorization_complete",
                  "user_name": "用户992704",
                  "user_open_id": "ou_23940d55731702db489089d071353548"
                }
                """);
        LarkIMAuthService service = new LarkIMAuthService(tool, new ObjectMapper(), new StubProfileResolver("profile-123"));

        LarkAdminAuthorizationInfoResponse response = service.waitForLarkAdminAuthorization("device-123");

        assertThat(tool.deviceCode).isEqualTo("device-123");
        assertThat(tool.completedProfileName).isEqualTo("profile-123");
        assertThat(response.event()).isEqualTo("authorization_complete");
        assertThat(response.userOpenId()).isEqualTo("ou_23940d55731702db489089d071353548");
        assertThat(response.userName()).isEqualTo("用户992704");
    }

    @Test
    void shouldUseDefaultProfileWhenResolverReturnsNull() {
        StubLarkAdminAuthorizationTool tool = new StubLarkAdminAuthorizationTool("""
                {"event":"authorization_complete","user_open_id":"ou_123","user_name":"用户992704"}
                """);
        LarkIMAuthService service = new LarkIMAuthService(tool, new ObjectMapper(), new StubProfileResolver(null));

        service.startLarkAdminAuthorization();
        service.waitForLarkAdminAuthorization("device-123");

        assertThat(tool.startedProfileName).isNull();
        assertThat(tool.completedProfileName).isNull();
    }

    private static final class StubLarkAdminAuthorizationTool extends LarkAdminAuthorizationTool {

        private final String completionJson;
        private String startedProfileName;
        private String deviceCode;
        private String completedProfileName;

        StubLarkAdminAuthorizationTool(String completionJson) {
            super(null, null);
            this.completionJson = completionJson;
        }

        @Override
        public AdminAuthorizationSession startAdminAuthorization(String profileName) {
            startedProfileName = profileName;
            return new AdminAuthorizationSession(profileName, "device-456", "https://example.com/verify", 600,
                    new byte[]{1, 2, 3});
        }

        @Override
        public String waitForAdminAuthorization(String deviceCode, String profileName) {
            this.deviceCode = deviceCode;
            this.completedProfileName = profileName;
            return completionJson;
        }
    }

    private static final class StubProfileResolver extends LarkCliProfileResolver {

        private final String profileName;

        StubProfileResolver(String profileName) {
            super(null, null);
            this.profileName = profileName;
        }

        @Override
        public String resolveConfiguredAppProfileName() {
            return profileName;
        }
    }
}
