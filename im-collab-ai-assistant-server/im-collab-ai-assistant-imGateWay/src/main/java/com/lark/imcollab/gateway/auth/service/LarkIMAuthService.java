package com.lark.imcollab.gateway.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;

@Service
public class LarkIMAuthService implements IMAuthService {

    private final LarkAdminAuthorizationTool larkAdminAuthorizationTool;
    private final ObjectMapper objectMapper;

    public LarkIMAuthService(
            LarkAdminAuthorizationTool larkAdminAuthorizationTool,
            ObjectMapper objectMapper
    ) {
        this.larkAdminAuthorizationTool = larkAdminAuthorizationTool;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AdminAuthorizationProfile> listLarkAuthorizationProfiles() {
        return larkAdminAuthorizationTool.listAuthorizationProfiles();
    }

    @Override
    public AdminAuthorizationProfile createLarkAuthorizationProfile(AdminAuthorizationProfileCreateRequest request) {
        return larkAdminAuthorizationTool.createAuthorizationProfile(request);
    }

    @Override
    public LarkAdminAuthorizationStartResponse startLarkAdminAuthorization(AdminAuthorizationStartRequest request) {
        AdminAuthorizationSession session = larkAdminAuthorizationTool.startAdminAuthorization(request);
        return mapAuthorizationStart(session);
    }

    private LarkAdminAuthorizationStartResponse mapAuthorizationStart(AdminAuthorizationSession session) {
        return new LarkAdminAuthorizationStartResponse(
                session.profileName(),
                session.deviceCode(),
                session.verificationUrl(),
                session.expiresIn(),
                Base64.getEncoder().encodeToString(session.qrCodePng())
        );
    }

    @Override
    public LarkAdminAuthorizationInfoResponse waitForLarkAdminAuthorization(String deviceCode, String profileName) {
        try {
            String rawResult = larkAdminAuthorizationTool.waitForAdminAuthorization(
                    new AdminAuthorizationCompletionRequest(deviceCode, profileName)
            );
            return mapAuthorizationCompletion(rawResult);
        } catch (AuthorizationFailedException exception) {
            if (!isInvalidDeviceCode(exception)) {
                throw exception;
            }
            AdminAuthorizationStatus status = larkAdminAuthorizationTool.getCurrentAdminAuthorizationStatus(profileName);
            if (!isValidAuthorizedStatus(status)) {
                throw exception;
            }
            return mapAuthorizationStatus(status);
        }
    }

    @Override
    public AdminAuthorizationStatus getAdminAuthorizationStatus(String profileName) {
        return larkAdminAuthorizationTool.getCurrentAdminAuthorizationStatus(profileName);
    }

    private LarkAdminAuthorizationInfoResponse mapAuthorizationCompletion(String rawResult) {
        try {
            JsonNode root = objectMapper.readTree(rawResult);
            return new LarkAdminAuthorizationInfoResponse(
                    text(root, "event"),
                    text(root, "user_open_id"),
                    text(root, "user_name")
            );
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to parse Lark authorization completion result", exception);
        }
    }

    private LarkAdminAuthorizationInfoResponse mapAuthorizationStatus(AdminAuthorizationStatus status) {
        return new LarkAdminAuthorizationInfoResponse(
                "authorization_complete",
                status.userOpenId(),
                status.userName()
        );
    }

    private boolean isInvalidDeviceCode(AuthorizationFailedException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase().replace('-', '_').replace(' ', '_');
        return normalized.contains("device_code_is_invalid")
                || normalized.contains("device_code_invalid")
                || normalized.contains("invalid_device_code");
    }

    private boolean isValidAuthorizedStatus(AdminAuthorizationStatus status) {
        return status != null
                && "valid".equalsIgnoreCase(status.tokenStatus())
                && status.userOpenId() != null
                && !status.userOpenId().isBlank();
    }

    private String text(JsonNode root, String fieldName) {
        JsonNode field = root.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return null;
        }
        return field.asText();
    }

}
