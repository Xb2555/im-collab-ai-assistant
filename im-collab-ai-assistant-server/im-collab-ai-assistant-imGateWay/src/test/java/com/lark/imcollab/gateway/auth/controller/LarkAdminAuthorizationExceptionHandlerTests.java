package com.lark.imcollab.gateway.auth.controller;

import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationInfoResponse;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationStartResponse;
import com.lark.imcollab.gateway.auth.service.IMAuthService;
import com.lark.imcollab.skills.lark.auth.AuthorizationFailedException;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfile;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfileCreateRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStartRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LarkAdminAuthorizationExceptionHandlerTests {

    @Test
    void shouldMapAuthorizationFailedExceptionToFailedResponse() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LarkAdminAuthorizationController(new FailingAuthService()))
                .setControllerAdvice(new LarkAdminAuthorizationExceptionHandler())
                .build();

        mockMvc.perform(post("/api/auth/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceCode":"device-123","profileName":"profile-123"}
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
                .standaloneSetup(new LarkAdminAuthorizationController(new InvalidRequestAuthService()))
                .setControllerAdvice(new LarkAdminAuthorizationExceptionHandler())
                .build();

        mockMvc.perform(post("/api/auth/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceCode":"","profileName":""}
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
                .standaloneSetup(new LarkAdminAuthorizationController(new CliErrorAuthService()))
                .setControllerAdvice(new LarkAdminAuthorizationExceptionHandler())
                .build();

        mockMvc.perform(post("/api/auth/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "appId": "cli_test",
                                  "appSecret": "bad-secret",
                                  "profileName": "profile-123",
                                  "brand": "feishu"
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.event").value("authorization_failed"))
                .andExpect(jsonPath("$.errorType").value("lark_cli_error"))
                .andExpect(jsonPath("$.message").value("invalid app secret"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    private static final class FailingAuthService implements IMAuthService {

        @Override
        public List<AdminAuthorizationProfile> listLarkAuthorizationProfiles() {
            return List.of();
        }

        @Override
        public AdminAuthorizationProfile createLarkAuthorizationProfile(AdminAuthorizationProfileCreateRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LarkAdminAuthorizationStartResponse startLarkAdminAuthorization(AdminAuthorizationStartRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LarkAdminAuthorizationInfoResponse waitForLarkAdminAuthorization(String deviceCode, String profileName) {
            throw new AuthorizationFailedException("device code expired");
        }

        @Override
        public AdminAuthorizationStatus getAdminAuthorizationStatus(String profileName) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InvalidRequestAuthService implements IMAuthService {

        @Override
        public List<AdminAuthorizationProfile> listLarkAuthorizationProfiles() {
            return List.of();
        }

        @Override
        public AdminAuthorizationProfile createLarkAuthorizationProfile(AdminAuthorizationProfileCreateRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LarkAdminAuthorizationStartResponse startLarkAdminAuthorization(AdminAuthorizationStartRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LarkAdminAuthorizationInfoResponse waitForLarkAdminAuthorization(String deviceCode, String profileName) {
            throw new IllegalArgumentException("deviceCode must be provided");
        }

        @Override
        public AdminAuthorizationStatus getAdminAuthorizationStatus(String profileName) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CliErrorAuthService implements IMAuthService {

        @Override
        public List<AdminAuthorizationProfile> listLarkAuthorizationProfiles() {
            return List.of();
        }

        @Override
        public AdminAuthorizationProfile createLarkAuthorizationProfile(AdminAuthorizationProfileCreateRequest request) {
            throw new IllegalStateException("invalid app secret");
        }

        @Override
        public LarkAdminAuthorizationStartResponse startLarkAdminAuthorization(AdminAuthorizationStartRequest request) {
            throw new IllegalStateException("invalid app secret");
        }

        @Override
        public LarkAdminAuthorizationInfoResponse waitForLarkAdminAuthorization(String deviceCode, String profileName) {
            throw new IllegalStateException("invalid app secret");
        }

        @Override
        public AdminAuthorizationStatus getAdminAuthorizationStatus(String profileName) {
            throw new IllegalStateException("invalid app secret");
        }
    }
}
