package com.lark.imcollab.gateway.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.cli.auth.dto.AdminAuthorizationRequest;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationInfoResponse;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationStartResponse;
import com.lark.imcollab.skills.lark.auth.LarkAdminAuthorizationTool;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationCompletionRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationSession;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import java.util.stream.StreamSupport;

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
    public LarkAdminAuthorizationStartResponse startLarkAdminAuthorization(AdminAuthorizationRequest request) {
        AdminAuthorizationSession session = larkAdminAuthorizationTool.startAdminAuthorization(request);
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
                new AdminAuthorizationCompletionRequest(deviceCode)
        );
        return mapAuthorizationCompletion(rawResult);
    }

    @Override
    public AdminAuthorizationStatus getAdminAuthorizationStatus() {
        return larkAdminAuthorizationTool.getCurrentAdminAuthorizationStatus();
    }

    private LarkAdminAuthorizationInfoResponse mapAuthorizationCompletion(String rawResult) {
        try {
            JsonNode root = objectMapper.readTree(rawResult);
            return new LarkAdminAuthorizationInfoResponse(
                    text(root, "event"),
                    text(root, "user_open_id"),
                    text(root, "user_name"),
                    stringList(root, "requested"),
                    stringList(root, "granted"),
                    stringList(root, "newly_granted"),
                    stringList(root, "already_granted"),
                    stringList(root, "missing")
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

    private List<String> stringList(JsonNode root, String fieldName) {
        JsonNode field = root.path(fieldName);
        if (!field.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(field.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }
}
