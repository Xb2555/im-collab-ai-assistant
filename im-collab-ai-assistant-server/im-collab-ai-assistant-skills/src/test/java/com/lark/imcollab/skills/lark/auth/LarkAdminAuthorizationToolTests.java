package com.lark.imcollab.skills.lark.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationCompletionRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfile;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfileCreateRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStartRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationSession;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStatus;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliCommandExecutor;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LarkAdminAuthorizationToolTests {

    @Test
    void shouldListAuthorizationProfiles() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(0, """
                [
                  {"name":"default","appId":"cli_default","brand":"feishu","active":true,"user":"用户992704"},
                  {"name":"demo","appId":"cli_demo","brand":"feishu","active":false}
                ]
                """));

        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        List<AdminAuthorizationProfile> profiles = tool.listAuthorizationProfiles();

        assertThat(profiles).hasSize(2);
        assertThat(profiles.get(0).name()).isEqualTo("default");
        assertThat(profiles.get(0).active()).isTrue();
        assertThat(profiles.get(0).user()).isEqualTo("用户992704");
        assertThat(profiles.get(1).appId()).isEqualTo("cli_demo");
        assertThat(executor.recordedCommands().get(0).arguments())
                .containsExactly("profile", "list");
    }

    @Test
    void shouldCreateBotProfile() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(0, "profile added"));
        executor.enqueue(new CliCommandResult(0, "bot"));

        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        AdminAuthorizationProfile profile = tool.createAuthorizationProfile(
                new AdminAuthorizationProfileCreateRequest(
                        "cli_test",
                        "secret-value",
                        "profile-123",
                        "feishu"
                )
        );

        assertThat(profile.name()).isEqualTo("profile-123");
        assertThat(profile.appId()).isEqualTo("cli_test");
        assertThat(profile.brand()).isEqualTo("feishu");
        assertThat(executor.recordedCommands().get(0).arguments())
                .containsExactly("--profile", "profile-123", "profile", "add", "--name", "profile-123",
                        "--app-id", "cli_test", "--app-secret-stdin", "--brand", "feishu");
        assertThat(executor.recordedCommands().get(0).stdin()).isEqualTo("secret-value");
        assertThat(executor.recordedCommands().get(1).arguments())
                .containsExactly("--profile", "profile-123", "config", "default-as", "bot");
    }

    @Test
    void shouldStartAuthorizationSessionForSelectedProfile() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(0, """
                {"device_code":"device-123","expires_in":600,"verification_url":"https://example.com/verify?code=abc"}
                """));

        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        AdminAuthorizationSession session = tool.startAdminAuthorization(
                new AdminAuthorizationStartRequest("profile-123")
        );

        assertThat(session.profileName()).isEqualTo("profile-123");
        assertThat(session.deviceCode()).isEqualTo("device-123");
        assertThat(session.qrCodePng()).isNotEmpty();
        assertThat(executor.recordedCommands()).hasSize(1);
        List<String> arguments = executor.recordedCommands().get(0).arguments();
        assertThat(arguments)
                .containsExactly("--profile", "profile-123", "auth", "login", "--json", "--no-wait", "--scope",
                        arguments.get(arguments.indexOf("--scope") + 1));
        assertThat(arguments.get(arguments.indexOf("--scope") + 1))
                .contains("bitable:app")
                .contains("docs:document.content:read")
                .contains("im:message")
                .contains("docx:document:create")
                .contains("slides:presentation:create")
                .doesNotContain("offline_access");
    }

    @Test
    void shouldRejectAuthorizationCompletionWithoutProfile() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        assertThatThrownBy(() -> tool.waitForAdminAuthorization(new AdminAuthorizationCompletionRequest(
                "device-123",
                null
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("profileName must be provided");
    }

    @Test
    void shouldWaitForAuthorizationCompletionByDeviceCodeAndProfile() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(0, """
                {"status":"authorized","profile":"profile-123"}
                """));

        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        String result = tool.waitForAdminAuthorization(new AdminAuthorizationCompletionRequest(
                "device-123",
                "profile-123"
        ));

        assertThat(result).isEqualTo("{\"status\":\"authorized\",\"profile\":\"profile-123\"}");
        assertThat(executor.recordedCommands().get(0).arguments())
                .containsExactly("--profile", "profile-123", "auth", "login", "--json", "--device-code",
                        "device-123");
        assertThat(executor.recordedCommands().get(0).timeoutMillis()).isEqualTo(10000);
    }

    @Test
    void shouldReturnPendingWhenAuthorizationCompletionTimesOut() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(124, "lark-cli command timed out"));

        LarkCliProperties properties = new LarkCliProperties();
        properties.setAuthorizationCompletionTimeoutMillis(1000);
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        assertThatThrownBy(() -> tool.waitForAdminAuthorization(new AdminAuthorizationCompletionRequest(
                "device-123",
                "profile-123"
        )))
                .isInstanceOf(AuthorizationPendingException.class)
                .hasMessage("Authorization is not completed yet. Retry later.");
        assertThat(executor.recordedCommands().get(0).timeoutMillis()).isEqualTo(1000);
    }

    @Test
    void shouldReturnPendingWhenCliReportsAuthorizationPending() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(1, """
                {"error":{"type":"authorization_pending","message":"authorization pending"}}
                """));

        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        assertThatThrownBy(() -> tool.waitForAdminAuthorization(new AdminAuthorizationCompletionRequest(
                "device-123",
                "profile-123"
        )))
                .isInstanceOf(AuthorizationPendingException.class)
                .hasMessage("Authorization is not completed yet. Retry later.");
        assertThat(executor.recordedCommands().get(0).arguments())
                .containsExactly("--profile", "profile-123", "auth", "login", "--json", "--device-code",
                        "device-123");
    }

    @Test
    void shouldReturnFailedWhenAuthorizationCompletionFails() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(1, """
                {"error":{"message":"device code expired"}}
                """));

        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        assertThatThrownBy(() -> tool.waitForAdminAuthorization(new AdminAuthorizationCompletionRequest(
                "device-123",
                "profile-123"
        )))
                .isInstanceOf(AuthorizationFailedException.class)
                .hasMessage("device code expired");
        assertThat(executor.recordedCommands().get(0).arguments())
                .containsExactly("--profile", "profile-123", "auth", "login", "--json", "--device-code",
                        "device-123");
    }

    @Test
    void shouldIgnoreCliNoticeAroundAuthorizationCompletionJson() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(0, """
                [lark-cli] device-flow: token obtained successfully
                {"event":"authorization_complete","requested":["calendar:calendar:readonly"],"user_open_id":"ou_123"}
                lark-cli auth login completed
                """));

        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        String result = tool.waitForAdminAuthorization(new AdminAuthorizationCompletionRequest(
                "device-123",
                "profile-123"
        ));

        assertThat(result).isEqualTo(
                "{\"event\":\"authorization_complete\",\"requested\":[\"calendar:calendar:readonly\"],\"user_open_id\":\"ou_123\"}"
        );
    }

    @Test
    void shouldReturnCurrentAuthorizationStatus() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(0, """
                {
                  "appId": "cli_a9564b61cdf8dcd3",
                  "brand": "feishu",
                  "defaultAs": "auto",
                  "expiresAt": "2026-04-25T17:39:16+08:00",
                  "grantedAt": "2026-04-25T15:39:16+08:00",
                  "identity": "user",
                  "refreshExpiresAt": "2026-05-02T15:39:16+08:00",
                  "scope": "auth:user.id:read calendar:calendar:readonly offline_access",
                  "tokenStatus": "valid",
                  "userName": "用户992704",
                  "userOpenId": "ou_23940d55731702db489089d071353548"
                }
                """));

        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        AdminAuthorizationStatus status = tool.getCurrentAdminAuthorizationStatus("profile-123");

        assertThat(status.appId()).isEqualTo("cli_a9564b61cdf8dcd3");
        assertThat(status.brand()).isEqualTo("feishu");
        assertThat(status.defaultAs()).isEqualTo("auto");
        assertThat(status.identity()).isEqualTo("user");
        assertThat(status.tokenStatus()).isEqualTo("valid");
        assertThat(status.userName()).isEqualTo("用户992704");
        assertThat(status.userOpenId()).isEqualTo("ou_23940d55731702db489089d071353548");
        assertThat(status.grantedAt()).isEqualTo("2026-04-25T15:39:16+08:00");
        assertThat(status.expiresAt()).isEqualTo("2026-04-25T17:39:16+08:00");
        assertThat(status.refreshExpiresAt()).isEqualTo("2026-05-02T15:39:16+08:00");
        assertThat(status.scopes()).containsExactly(
                "auth:user.id:read",
                "calendar:calendar:readonly",
                "offline_access"
        );
        assertThat(executor.recordedCommands().get(0).arguments())
                .containsExactly("--profile", "profile-123", "auth", "status");
    }

    @Test
    void shouldUseHardcodedAuthorizationScopes() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(0, """
                {"device_code":"device-456","expires_in":600,"verification_url":"https://example.com/verify?code=xyz"}
                """));

        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        AdminAuthorizationSession session = tool.startAdminAuthorization(new AdminAuthorizationStartRequest(
                "profile-123"
        ));

        assertThat(session.qrCodePng()).isNotEmpty();
        List<String> arguments = executor.recordedCommands().get(0).arguments();
        assertThat(arguments).contains("--scope");
        assertThat(arguments).doesNotContain("--recommend", "--domain");
        assertThat(arguments.get(arguments.indexOf("--scope") + 1))
                .contains("bitable:app:readonly")
                .contains("board:whiteboard:node:create")
                .contains("board:whiteboard:node:update")
                .contains("docs:document:copy")
                .contains("drive:file:upload")
                .contains("im:message:send_as_bot")
                .contains("sheets:spreadsheet:create")
                .contains("slides:presentation:update")
                .contains("wiki:node:create")
                .doesNotContain("calendar:calendar:readonly");
    }

    private static final class StubCliCommandExecutor implements CliCommandExecutor {

        private final Queue<CliCommandResult> responses = new ArrayDeque<>();
        private final List<CliCommand> commands = new ArrayList<>();

        void enqueue(CliCommandResult result) {
            responses.add(result);
        }

        List<CliCommand> recordedCommands() {
            return commands;
        }

        @Override
        public CliCommandResult execute(CliCommand command) throws IOException {
            commands.add(command);
            CliCommandResult result = responses.poll();
            if (result == null) {
                throw new IOException("No stub response configured");
            }
            return result;
        }
    }
}
