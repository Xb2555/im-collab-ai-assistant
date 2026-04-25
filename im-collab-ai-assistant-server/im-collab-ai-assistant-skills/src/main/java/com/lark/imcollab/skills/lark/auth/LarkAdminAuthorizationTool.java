package com.lark.imcollab.skills.lark.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.lark.imcollab.common.cli.auth.dto.AdminAuthorizationRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationCompletionRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationSession;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStatus;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class LarkAdminAuthorizationTool {

    private final LarkCliClient larkCliClient;
    private final LarkCliProperties properties;

    public LarkAdminAuthorizationTool(
            LarkCliClient larkCliClient,
            LarkCliProperties properties
    ) {
        this.larkCliClient = larkCliClient;
        this.properties = properties;
    }

    @Tool(description = "Create a Lark administrator authorization QR-code PNG via lark-cli device authorization flow. Returns raw PNG bytes.")
    public byte[] getAuthQrCodePng(AdminAuthorizationRequest request) {
        return startAdminAuthorization(request).qrCodePng();
    }

    @Tool(description = "Scenario A/B: start Lark administrator authorization for an agent. Returns the QR-code PNG plus deviceCode for a later waitForAdminAuthorization tool call.")
    public AdminAuthorizationSession startAdminAuthorization(AdminAuthorizationRequest request) {
        List<String> scopes = normalize(request.scopes());
        List<String> domains = normalize(request.domains());
        if (scopes.isEmpty() && domains.isEmpty()) {
            throw new IllegalArgumentException("Either scopes or domains must be provided");
        }

        JsonNode authStart = larkCliClient.executeJson(buildStartArgs(scopes, domains, request.recommend()));
        String deviceCode = requireText(authStart, "device_code");
        String verificationUrl = requireText(authStart, "verification_url");
        int expiresIn = authStart.path("expires_in").asInt();
        return new AdminAuthorizationSession(deviceCode, verificationUrl, expiresIn, generateQrCode(verificationUrl));
    }

    @Tool(description = "Scenario A/B: wait until the Lark administrator completes scanning and authorization for a previous deviceCode. Returns the lark-cli JSON result.")
    public String waitForAdminAuthorization(AdminAuthorizationCompletionRequest request) {
        String deviceCode = normalizeDeviceCode(request.deviceCode());
        JsonNode authResult = larkCliClient.executeJson(List.of(
                "auth", "login", "--json", "--device-code", deviceCode
        ));
        return authResult.toString();
    }

    @Tool(description = "Scenario A/B: view current Lark administrator authorization status, including user identity, token status, expiration times, and granted scopes.")
    public AdminAuthorizationStatus getCurrentAdminAuthorizationStatus() {
        JsonNode authStatus = larkCliClient.executeJson(List.of("auth", "status"));
        return new AdminAuthorizationStatus(
                optionalText(authStatus, "appId"),
                optionalText(authStatus, "brand"),
                optionalText(authStatus, "defaultAs"),
                optionalText(authStatus, "identity"),
                optionalText(authStatus, "tokenStatus"),
                optionalText(authStatus, "userName"),
                optionalText(authStatus, "userOpenId"),
                optionalText(authStatus, "grantedAt"),
                optionalText(authStatus, "expiresAt"),
                optionalText(authStatus, "refreshExpiresAt"),
                splitScopes(optionalText(authStatus, "scope"))
        );
    }

    private List<String> buildStartArgs(
            List<String> scopes,
            List<String> domains,
            boolean recommend
    ) {
        List<String> args = new ArrayList<>(List.of("auth", "login", "--json", "--no-wait"));
        if (recommend) {
            args.add("--recommend");
        }
        if (!scopes.isEmpty()) {
            args.add("--scope");
            args.add(String.join(" ", scopes));
        }
        for (String domain : domains) {
            args.add("--domain");
            args.add(domain);
        }
        return args;
    }

    private List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String requireText(JsonNode root, String fieldName) {
        JsonNode field = root.path(fieldName);
        if (field.isMissingNode() || field.asText().isBlank()) {
            throw new IllegalStateException("lark-cli response missing field: " + fieldName);
        }
        return field.asText();
    }

    private String optionalText(JsonNode root, String fieldName) {
        JsonNode field = root.path(fieldName);
        if (field.isMissingNode() || field.isNull() || field.asText().isBlank()) {
            return null;
        }
        return field.asText();
    }

    private List<String> splitScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return List.of();
        }
        return List.of(scope.trim().split("\\s+"));
    }

    private String normalizeDeviceCode(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new IllegalArgumentException("deviceCode must be provided");
        }
        return deviceCode.trim();
    }

    private byte[] generateQrCode(String content) {
        try {
            BitMatrix bitMatrix = new MultiFormatWriter()
                    .encode(content, BarcodeFormat.QR_CODE, properties.getQrCodeSize(), properties.getQrCodeSize());
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
            return outputStream.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to generate QR code", exception);
        }
    }
}
