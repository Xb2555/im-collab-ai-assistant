package com.lark.imcollab.skills.lark.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationCompletionRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfile;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfileCreateRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStartRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationSession;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStatus;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Component
public class LarkAdminAuthorizationTool {

    private static final List<String> AUTHORIZATION_SCOPES = List.of(
            "bitable:app",
            "bitable:app:readonly",
            "board:whiteboard:node:create",
            "board:whiteboard:node:delete",
            "board:whiteboard:node:read",
            "board:whiteboard:node:update",
            "docs:doc",
            "docs:doc:readonly",
            "docs:document.comment:create",
            "docs:document.comment:delete",
            "docs:document.comment:read",
            "docs:document.comment:update",
            "docs:document.comment:write_only",
            "docs:document.content:read",
            "docs:document.media:download",
            "docs:document.media:upload",
            "docs:document.subscription",
            "docs:document.subscription:read",
            "docs:document:copy",
            "docs:document:export",
            "docs:document:import",
            "docs:event.document_deleted:read",
            "docs:event.document_edited:read",
            "docs:event.document_opened:read",
            "docs:event:subscribe",
            "docs:permission.member",
            "docs:permission.member:auth",
            "docs:permission.member:create",
            "docs:permission.member:delete",
            "docs:permission.member:readonly",
            "docs:permission.member:retrieve",
            "docs:permission.member:transfer",
            "docs:permission.member:update",
            "docs:permission.setting",
            "docs:permission.setting:read",
            "docs:permission.setting:readonly",
            "docs:permission.setting:write_only",
            "docx:document",
            "docx:document.block:convert",
            "docx:document:create",
            "docx:document:readonly",
            "docx:document:write_only",
            "drive:drive",
            "drive:drive.metadata:readonly",
            "drive:drive.search:readonly",
            "drive:drive:readonly",
            "drive:drive:version",
            "drive:drive:version:readonly",
            "drive:export:readonly",
            "drive:file",
            "drive:file.like:readonly",
            "drive:file.meta.sec_label.read_only",
            "drive:file:download",
            "drive:file:readonly",
            "drive:file:upload",
            "drive:file:view_record:readonly",
            "im:app_feed_card:write",
            "im:biz_entity_tag_relation:read",
            "im:biz_entity_tag_relation:write",
            "im:chat",
            "im:chat.access_event.bot_p2p_chat:read",
            "im:chat.announcement:read",
            "im:chat.announcement:write_only",
            "im:chat.chat_pins:read",
            "im:chat.chat_pins:write_only",
            "im:chat.collab_plugins:read",
            "im:chat.collab_plugins:write_only",
            "im:chat.managers:write_only",
            "im:chat.members:bot_access",
            "im:chat.members:read",
            "im:chat.members:write_only",
            "im:chat.menu_tree:read",
            "im:chat.menu_tree:write_only",
            "im:chat.moderation:read",
            "im:chat.tabs:read",
            "im:chat.tabs:write_only",
            "im:chat.top_notice:write_only",
            "im:chat.widgets:read",
            "im:chat.widgets:write_only",
            "im:chat:create",
            "im:chat:delete",
            "im:chat:moderation:write_only",
            "im:chat:operate_as_owner",
            "im:chat:read",
            "im:chat:readonly",
            "im:chat:update",
            "im:datasync.feed_card.time_sensitive:write",
            "im:message",
            "im:message.group_at_msg.include_bot:readonly",
            "im:message.group_at_msg:readonly",
            "im:message.group_msg",
            "im:message.p2p_msg:readonly",
            "im:message.pins:read",
            "im:message.pins:write_only",
            "im:message.reactions:read",
            "im:message.reactions:write_only",
            "im:message.urgent",
            "im:message.urgent.status:write",
            "im:message.urgent:phone",
            "im:message.urgent:sms",
            "im:message:readonly",
            "im:message:recall",
            "im:message:send_as_bot",
            "im:message:send_multi_depts",
            "im:message:send_multi_users",
            "im:message:send_sys_msg",
            "im:message:update",
            "im:resource",
            "im:tag:read",
            "im:tag:write",
            "im:url_preview.update",
            "im:user_agent:read",
            "sheets:spreadsheet",
            "sheets:spreadsheet.meta:read",
            "sheets:spreadsheet.meta:write_only",
            "sheets:spreadsheet:create",
            "sheets:spreadsheet:read",
            "sheets:spreadsheet:readonly",
            "sheets:spreadsheet:write_only",
            "slides:presentation:create",
            "slides:presentation:read",
            "slides:presentation:update",
            "slides:presentation:write_only",
            "space:document.event:read",
            "space:document:delete",
            "space:document:move",
            "space:document:retrieve",
            "space:document:shortcut",
            "space:folder:create",
            "wiki:member:create",
            "wiki:member:retrieve",
            "wiki:member:update",
            "wiki:node:copy",
            "wiki:node:create",
            "wiki:node:move",
            "wiki:node:read",
            "wiki:node:retrieve",
            "wiki:node:update",
            "wiki:setting:read",
            "wiki:setting:write_only",
            "wiki:space:read",
            "wiki:space:retrieve",
            "wiki:space:write_only",
            "wiki:wiki",
            "wiki:wiki:readonly"
    );

    private static final List<String> AUTHORIZATION_DOMAINS = List.of();

    private final LarkCliClient larkCliClient;
    private final LarkCliProperties properties;

    public LarkAdminAuthorizationTool(
            LarkCliClient larkCliClient,
            LarkCliProperties properties
    ) {
        this.larkCliClient = larkCliClient;
        this.properties = properties;
    }

    @Tool(description = "Scenario A/B: list Lark CLI profiles available for app selection.")
    public List<AdminAuthorizationProfile> listAuthorizationProfiles() {
        JsonNode profiles = larkCliClient.executeJson(List.of("profile", "list"));
        if (!profiles.isArray()) {
            return List.of();
        }
        List<AdminAuthorizationProfile> result = new ArrayList<>();
        for (JsonNode profile : profiles) {
            result.add(new AdminAuthorizationProfile(
                    optionalText(profile, "name"),
                    optionalText(profile, "appId"),
                    optionalText(profile, "brand"),
                    profile.path("active").asBoolean(false),
                    optionalText(profile, "user")
            ));
        }
        return result;
    }

    @Tool(description = "Scenario A/B: create a Lark CLI profile for a selected app and set default identity to bot.")
    public AdminAuthorizationProfile createAuthorizationProfile(AdminAuthorizationProfileCreateRequest request) {
        String appId = requireValue(request.appId(), "appId");
        String appSecret = requireValue(request.appSecret(), "appSecret");
        String profileName = normalizeProfileName(request.profileName());
        String brand = normalizeBrand(request.brand());

        executeCli(List.of(
                "--profile", profileName,
                "profile", "add",
                "--name", profileName,
                "--app-id", appId,
                "--app-secret-stdin",
                "--brand", brand
        ), appSecret);
        executeCli(List.of(
                "--profile", profileName,
                "config", "default-as", "bot"
        ), null);
        return new AdminAuthorizationProfile(profileName, appId, brand, false, null);
    }

    @Tool(description = "Scenario A/B: start user authorization for a selected Lark CLI profile.")
    public AdminAuthorizationSession startAdminAuthorization(AdminAuthorizationStartRequest request) {
        String profileName = requireValue(request.profileName(), "profileName");
        JsonNode authStart = larkCliClient.executeJson(buildStartArgs(
                profileName
        ));
        String deviceCode = requireText(authStart, "device_code");
        String verificationUrl = requireText(authStart, "verification_url");
        int expiresIn = authStart.path("expires_in").asInt();
        return new AdminAuthorizationSession(
                profileName,
                deviceCode,
                verificationUrl,
                expiresIn,
                generateQrCode(verificationUrl)
        );
    }

    @Tool(description = "Scenario A/B: wait until the Lark administrator completes scanning and authorization for a previous deviceCode. Returns the lark-cli JSON result.")
    public String waitForAdminAuthorization(AdminAuthorizationCompletionRequest request) {
        String deviceCode = normalizeDeviceCode(request.deviceCode());
        String profileName = requireValue(request.profileName(), "profileName");
        CliCommandResult result = larkCliClient.execute(
                buildCompleteArgs(profileName, deviceCode),
                null,
                properties.getAuthorizationCompletionTimeoutMillis()
        );
        if (!result.isSuccess()) {
            if (isCompletionTimeout(result.output()) || isAuthorizationPending(result.output())) {
                throw new AuthorizationPendingException("Authorization is not completed yet. Retry later.");
            }
            throw new AuthorizationFailedException(larkCliClient.extractErrorMessage(result.output()));
        }
        try {
            return larkCliClient.readJsonOutput(result.output()).toString();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to parse lark-cli JSON output", exception);
        }
    }

    @Tool(description = "Scenario A/B: view current Lark administrator authorization status under a specific profile.")
    public AdminAuthorizationStatus getCurrentAdminAuthorizationStatus(String profileName) {
        JsonNode authStatus = larkCliClient.executeJson(buildProfileArgs(requireValue(profileName, "profileName"),
                "auth", "status"));
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

    private List<String> buildStartArgs(String profileName) {
        List<String> args = new ArrayList<>(buildProfileArgs(profileName, "auth", "login", "--json", "--no-wait"));
        if (!AUTHORIZATION_SCOPES.isEmpty()) {
            args.add("--scope");
            args.add(String.join(" ", AUTHORIZATION_SCOPES));
        }
        for (String domain : AUTHORIZATION_DOMAINS) {
            args.add("--domain");
            args.add(domain);
        }
        return args;
    }

    private List<String> buildCompleteArgs(String profileName, String deviceCode) {
        List<String> args = new ArrayList<>(buildProfileArgs(profileName, "auth", "login", "--json"));
        args.add("--device-code");
        args.add(deviceCode);
        return args;
    }

    private List<String> buildProfileArgs(String profileName, String... args) {
        List<String> command = new ArrayList<>();
        if (profileName != null && !profileName.isBlank()) {
            command.add("--profile");
            command.add(profileName.trim());
        }
        command.addAll(List.of(args));
        return command;
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

    private String normalizeProfileName(String profileName) {
        if (profileName != null && !profileName.isBlank()) {
            return profileName.trim();
        }
        return "imcollab-auth-" + UUID.randomUUID();
    }

    private String normalizeBrand(String brand) {
        if (brand == null || brand.isBlank()) {
            return "feishu";
        }
        return brand.trim();
    }

    private String requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
        return value.trim();
    }

    private void executeCli(List<String> args, String stdin) {
        var result = larkCliClient.execute(args, stdin);
        if (!result.isSuccess()) {
            throw new IllegalStateException(larkCliClient.extractErrorMessage(result.output()));
        }
    }

    private boolean isCompletionTimeout(String output) {
        return output != null && output.contains("lark-cli command timed out");
    }

    private boolean isAuthorizationPending(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        try {
            JsonNode root = larkCliClient.readJsonOutput(output);
            if (containsPendingMarker(
                    optionalText(root, "event"),
                    optionalText(root, "status"),
                    optionalText(root.path("error"), "type"),
                    optionalText(root.path("error"), "message")
            )) {
                return true;
            }
        } catch (IOException ignored) {
            // Fall back to raw output matching.
        }
        return containsPendingMarker(output);
    }

    private boolean containsPendingMarker(String... values) {
        return Stream.of(values)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase().replace('-', '_'))
                .anyMatch(value ->
                        value.contains("authorization_pending")
                                || value.contains("slow_down")
                                || value.contains("not completed")
                                || value.contains("not complete")
                );
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
