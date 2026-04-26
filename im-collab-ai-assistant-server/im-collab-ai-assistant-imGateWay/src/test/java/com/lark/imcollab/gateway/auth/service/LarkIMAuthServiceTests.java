package com.lark.imcollab.gateway.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationInfoResponse;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationStartResponse;
import com.lark.imcollab.skills.lark.auth.AuthorizationFailedException;
import com.lark.imcollab.skills.lark.auth.LarkAdminAuthorizationTool;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationCompletionRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfile;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfileCreateRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStartRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationSession;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LarkIMAuthServiceTests {

    @Test
    void shouldMapAuthorizationCompletionJsonToAdminUserInfo() {
        StubLarkAdminAuthorizationTool tool = new StubLarkAdminAuthorizationTool("""
                {
                  "already_granted": ["calendar:calendar:readonly"],
                  "event": "authorization_complete",
                  "granted": ["auth:user.id:read", "calendar:calendar:readonly"],
                  "missing": [],
                  "newly_granted": ["auth:user.id:read"],
                  "requested": ["calendar:calendar:readonly"],
                  "scope": "auth:user.id:read calendar:calendar:readonly",
                  "user_name": "用户992704",
                  "user_open_id": "ou_23940d55731702db489089d071353548"
                }
                """);
        LarkIMAuthService service = new LarkIMAuthService(tool, new ObjectMapper());

        LarkAdminAuthorizationInfoResponse response = service.waitForLarkAdminAuthorization("device-123", "profile-123");

        assertThat(tool.deviceCode).isEqualTo("device-123");
        assertThat(tool.profileName).isEqualTo("profile-123");
        assertThat(response.event()).isEqualTo("authorization_complete");
        assertThat(response.userOpenId()).isEqualTo("ou_23940d55731702db489089d071353548");
        assertThat(response.userName()).isEqualTo("用户992704");
    }

    @Test
    void shouldTreatInvalidDeviceCodeAsCompleteWhenProfileAlreadyAuthorized() {
        StubLarkAdminAuthorizationTool tool = new StubLarkAdminAuthorizationTool(
                new AuthorizationFailedException(
                        "authorization failed: The device_code is invalid. Please restart the device authorization flow."
                )
        );
        LarkIMAuthService service = new LarkIMAuthService(tool, new ObjectMapper());

        LarkAdminAuthorizationInfoResponse response = service.waitForLarkAdminAuthorization("device-123",
                "profile-123");

        assertThat(tool.deviceCode).isEqualTo("device-123");
        assertThat(tool.profileName).isEqualTo("profile-123");
        assertThat(tool.statusProfileName).isEqualTo("profile-123");
        assertThat(response.event()).isEqualTo("authorization_complete");
        assertThat(response.userOpenId()).isEqualTo("ou_123");
        assertThat(response.userName()).isEqualTo("用户992704");
    }

    @Test
    void shouldListProfiles() {
        StubLarkAdminAuthorizationTool tool = new StubLarkAdminAuthorizationTool("{}");
        LarkIMAuthService service = new LarkIMAuthService(tool, new ObjectMapper());

        List<AdminAuthorizationProfile> profiles = service.listLarkAuthorizationProfiles();

        assertThat(profiles).hasSize(1);
        assertThat(profiles.get(0).name()).isEqualTo("profile-123");
        assertThat(profiles.get(0).appId()).isEqualTo("cli_test");
    }

    @Test
    void shouldCreateProfile() {
        StubLarkAdminAuthorizationTool tool = new StubLarkAdminAuthorizationTool("{}");
        LarkIMAuthService service = new LarkIMAuthService(tool, new ObjectMapper());

        AdminAuthorizationProfile profile = service.createLarkAuthorizationProfile(
                new AdminAuthorizationProfileCreateRequest(
                        "cli_test",
                        "secret-value",
                        "profile-123",
                        "feishu"
                )
        );

        assertThat(tool.createProfileRequest.appId()).isEqualTo("cli_test");
        assertThat(tool.createProfileRequest.appSecret()).isEqualTo("secret-value");
        assertThat(profile.name()).isEqualTo("profile-123");
        assertThat(profile.appId()).isEqualTo("cli_test");
    }

    @Test
    void shouldStartAuthorizationWithSelectedProfile() {
        StubLarkAdminAuthorizationTool tool = new StubLarkAdminAuthorizationTool("{}");
        LarkIMAuthService service = new LarkIMAuthService(tool, new ObjectMapper());

        LarkAdminAuthorizationStartResponse response = service.startLarkAdminAuthorization(
                new AdminAuthorizationStartRequest("profile-123")
        );

        assertThat(tool.startRequest.profileName()).isEqualTo("profile-123");
        assertThat(response.profileName()).isEqualTo("profile-123");
        assertThat(response.deviceCode()).isEqualTo("device-456");
        assertThat(response.qrCodePngBase64()).isEqualTo("AQID");
    }

    @Test
    void shouldGetAuthorizationStatusByProfile() {
        StubLarkAdminAuthorizationTool tool = new StubLarkAdminAuthorizationTool("{}");
        LarkIMAuthService service = new LarkIMAuthService(tool, new ObjectMapper());

        AdminAuthorizationStatus status = service.getAdminAuthorizationStatus("profile-123");

        assertThat(tool.statusProfileName).isEqualTo("profile-123");
        assertThat(status.appId()).isEqualTo("cli_test");
        assertThat(status.tokenStatus()).isEqualTo("valid");
    }

    private static final class StubLarkAdminAuthorizationTool extends LarkAdminAuthorizationTool {

        private final String completionJson;
        private final RuntimeException completionException;
        private AdminAuthorizationProfileCreateRequest createProfileRequest;
        private AdminAuthorizationStartRequest startRequest;
        private String deviceCode;
        private String profileName;
        private String statusProfileName;

        StubLarkAdminAuthorizationTool(String completionJson) {
            super(null, null);
            this.completionJson = completionJson;
            this.completionException = null;
        }

        StubLarkAdminAuthorizationTool(RuntimeException completionException) {
            super(null, null);
            this.completionJson = null;
            this.completionException = completionException;
        }

        @Override
        public List<AdminAuthorizationProfile> listAuthorizationProfiles() {
            return List.of(new AdminAuthorizationProfile("profile-123", "cli_test", "feishu", true, "用户992704"));
        }

        @Override
        public AdminAuthorizationProfile createAuthorizationProfile(AdminAuthorizationProfileCreateRequest request) {
            createProfileRequest = request;
            return new AdminAuthorizationProfile("profile-123", "cli_test", "feishu", false, null);
        }

        @Override
        public AdminAuthorizationSession startAdminAuthorization(AdminAuthorizationStartRequest request) {
            startRequest = request;
            return new AdminAuthorizationSession("profile-123", "device-456", "https://example.com/verify", 600,
                    new byte[]{1, 2, 3});
        }

        @Override
        public String waitForAdminAuthorization(AdminAuthorizationCompletionRequest request) {
            deviceCode = request.deviceCode();
            profileName = request.profileName();
            if (completionException != null) {
                throw completionException;
            }
            return completionJson;
        }

        @Override
        public AdminAuthorizationStatus getCurrentAdminAuthorizationStatus(String profileName) {
            statusProfileName = profileName;
            return new AdminAuthorizationStatus("cli_test", "feishu", "bot", "user", "valid", "用户992704",
                    "ou_123", null, null, null, List.of("calendar:calendar:readonly"));
        }
    }
}
