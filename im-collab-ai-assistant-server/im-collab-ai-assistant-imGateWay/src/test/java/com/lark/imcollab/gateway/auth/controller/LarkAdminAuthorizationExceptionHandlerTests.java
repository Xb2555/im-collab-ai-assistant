package com.lark.imcollab.gateway.auth.controller;

import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationInfoResponse;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationStartResponse;
import com.lark.imcollab.gateway.auth.service.IMAuthService;
import com.lark.imcollab.gateway.im.service.LarkCliProfileResolver;
import com.lark.imcollab.gateway.im.service.LarkIMListenerService;
import com.lark.imcollab.gateway.im.service.LarkIMListenerStatusResponse;
import com.lark.imcollab.skills.lark.auth.AuthorizationFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LarkAdminAuthorizationExceptionHandlerTests {

    @Test
    void shouldStartBotListenerAfterAuthorizationCompletes() throws Exception {
        RecordingListenerService listenerService = new RecordingListenerService();
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LarkAdminAuthorizationController(
                        new CompleteAuthService(),
                        listenerService,
                        new StubProfileResolver("profile-123")
                ))
                .setControllerAdvice(new LarkAdminAuthorizationExceptionHandler())
                .build();

        mockMvc.perform(post("/api/auth/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceCode":"device-123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event").value("authorization_complete"))
                .andExpect(jsonPath("$.userOpenId").value("ou_123"))
                .andExpect(jsonPath("$.userName").value("用户992704"));

        assertThat(listenerService.startedProfileName).isEqualTo("profile-123");
    }

    @Test
    void shouldMapAuthorizationFailedExceptionToFailedResponse() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LarkAdminAuthorizationController(
                        new FailingAuthService(),
                        new RecordingListenerService(),
                        new StubProfileResolver("profile-123")
                ))
                .setControllerAdvice(new LarkAdminAuthorizationExceptionHandler())
                .build();

        mockMvc.perform(post("/api/auth/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceCode":"device-123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event").value("authorization_failed"))
                .andExpect(jsonPath("$.errorType").value("authorization_failed"))
                .andExpect(jsonPath("$.message").value("device code expired"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void shouldMapInvalidCompleteRequestToBadRequestFailedResponse() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LarkAdminAuthorizationController(
                        new InvalidRequestAuthService(),
                        new RecordingListenerService(),
                        new StubProfileResolver("profile-123")
                ))
                .setControllerAdvice(new LarkAdminAuthorizationExceptionHandler())
                .build();

        mockMvc.perform(post("/api/auth/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceCode":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.event").value("authorization_failed"))
                .andExpect(jsonPath("$.errorType").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("deviceCode must be provided"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void shouldMapCliRuntimeFailureToUsefulFailedResponse() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LarkAdminAuthorizationController(
                        new CliErrorAuthService(),
                        new RecordingListenerService(),
                        new StubProfileResolver("profile-123")
                ))
                .setControllerAdvice(new LarkAdminAuthorizationExceptionHandler())
                .build();

        mockMvc.perform(post("/api/auth/start"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.event").value("authorization_failed"))
                .andExpect(jsonPath("$.errorType").value("lark_cli_error"))
                .andExpect(jsonPath("$.message").value("invalid app secret"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    private static final class CompleteAuthService implements IMAuthService {

        @Override
        public LarkAdminAuthorizationStartResponse startLarkAdminAuthorization() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LarkAdminAuthorizationInfoResponse waitForLarkAdminAuthorization(String deviceCode) {
            return new LarkAdminAuthorizationInfoResponse("authorization_complete", "ou_123", "用户992704");
        }
    }

    private static final class RecordingListenerService extends LarkIMListenerService {

        private String startedProfileName;

        RecordingListenerService() {
            super(null, null, null);
        }

        @Override
        public LarkIMListenerStatusResponse startDefault(String profileName) {
            startedProfileName = profileName;
            return new LarkIMListenerStatusResponse(profileName == null ? "default" : profileName, true, "running",
                    null, null);
        }
    }

    private static final class FailingAuthService implements IMAuthService {

        @Override
        public LarkAdminAuthorizationStartResponse startLarkAdminAuthorization() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LarkAdminAuthorizationInfoResponse waitForLarkAdminAuthorization(String deviceCode) {
            throw new AuthorizationFailedException("device code expired");
        }
    }

    private static final class InvalidRequestAuthService implements IMAuthService {

        @Override
        public LarkAdminAuthorizationStartResponse startLarkAdminAuthorization() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LarkAdminAuthorizationInfoResponse waitForLarkAdminAuthorization(String deviceCode) {
            throw new IllegalArgumentException("deviceCode must be provided");
        }
    }

    private static final class CliErrorAuthService implements IMAuthService {

        @Override
        public LarkAdminAuthorizationStartResponse startLarkAdminAuthorization() {
            throw new IllegalStateException("invalid app secret");
        }

        @Override
        public LarkAdminAuthorizationInfoResponse waitForLarkAdminAuthorization(String deviceCode) {
            throw new IllegalStateException("invalid app secret");
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
