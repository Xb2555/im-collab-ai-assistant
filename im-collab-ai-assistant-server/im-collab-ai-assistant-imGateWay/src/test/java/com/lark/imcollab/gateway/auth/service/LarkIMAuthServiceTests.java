package com.lark.imcollab.gateway.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationInfoResponse;
import com.lark.imcollab.skills.lark.auth.LarkAdminAuthorizationTool;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationCompletionRequest;
import org.junit.jupiter.api.Test;

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

        LarkAdminAuthorizationInfoResponse response = service.waitForLarkAdminAuthorization("device-123");

        assertThat(tool.deviceCode).isEqualTo("device-123");
        assertThat(response.event()).isEqualTo("authorization_complete");
        assertThat(response.userOpenId()).isEqualTo("ou_23940d55731702db489089d071353548");
        assertThat(response.userName()).isEqualTo("用户992704");
        assertThat(response.requestedScopes()).containsExactly("calendar:calendar:readonly");
        assertThat(response.grantedScopes()).containsExactly("auth:user.id:read", "calendar:calendar:readonly");
        assertThat(response.newlyGrantedScopes()).containsExactly("auth:user.id:read");
        assertThat(response.alreadyGrantedScopes()).containsExactly("calendar:calendar:readonly");
        assertThat(response.missingScopes()).isEmpty();
    }

    private static final class StubLarkAdminAuthorizationTool extends LarkAdminAuthorizationTool {

        private final String completionJson;
        private String deviceCode;

        StubLarkAdminAuthorizationTool(String completionJson) {
            super(null, null);
            this.completionJson = completionJson;
        }

        @Override
        public String waitForAdminAuthorization(AdminAuthorizationCompletionRequest request) {
            deviceCode = request.deviceCode();
            return completionJson;
        }
    }
}
