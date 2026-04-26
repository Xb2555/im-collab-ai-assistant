package com.lark.imcollab.gateway.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationInfoResponse;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationStartResponse;
import com.lark.imcollab.skills.lark.auth.LarkAdminAuthorizationTool;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationSession;
import com.lark.imcollab.gateway.im.service.LarkCliProfileResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

@Service
public class LarkIMAuthService implements IMAuthService {

    private final LarkAdminAuthorizationTool larkAdminAuthorizationTool;
    private final ObjectMapper objectMapper;
    private final LarkCliProfileResolver profileResolver;

    public LarkIMAuthService(
            LarkAdminAuthorizationTool larkAdminAuthorizationTool,
            ObjectMapper objectMapper,
            LarkCliProfileResolver profileResolver
    ) {
        this.larkAdminAuthorizationTool = larkAdminAuthorizationTool;
        this.objectMapper = objectMapper;
        this.profileResolver = profileResolver;
    }

    @Override
    public LarkAdminAuthorizationStartResponse startLarkAdminAuthorization() {
        AdminAuthorizationSession session = larkAdminAuthorizationTool.startAdminAuthorization(
                profileResolver.resolveConfiguredAppProfileName()
        );
        return mapAuthorizationStart(session);
    }

    private LarkAdminAuthorizationStartResponse mapAuthorizationStart(AdminAuthorizationSession session) {
        return new LarkAdminAuthorizationStartResponse(
                session.deviceCode(),
                session.verificationUrl(),
                session.expiresIn(),
                Base64.getEncoder().encodeToString(session.qrCodePng())
        );
    }

    @Override
    public LarkAdminAuthorizationInfoResponse waitForLarkAdminAuthorization(String deviceCode) {
        String rawResult = larkAdminAuthorizationTool.waitForAdminAuthorization(
                deviceCode,
                profileResolver.resolveConfiguredAppProfileName()
        );
        return mapAuthorizationCompletion(rawResult);
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

    private String text(JsonNode root, String fieldName) {
        JsonNode field = root.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return null;
        }
        return field.asText();
    }

}
