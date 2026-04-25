package com.lark.imcollab.skills.lark.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.cli.auth.dto.AdminAuthorizationRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationCompletionRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationSession;
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
    void shouldReturnAuthorizationQrCodeBinary() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(0, """
                {"device_code":"device-123","expires_in":600,"verification_url":"https://example.com/verify?code=abc"}
                """));

        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        byte[] qrCode = tool.getAuthQrCodePng(new AdminAuthorizationRequest(
                List.of("calendar:calendar:readonly"),
                List.of(),
                false
        ));

        assertThat(qrCode).isNotEmpty();
        assertThat(executor.recordedCommands().get(0).arguments())
                .containsExactly("auth", "login", "--json", "--no-wait", "--scope",
                        "calendar:calendar:readonly");
    }

    @Test
    void shouldStartAuthorizationSessionForAgentToolUse() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(0, """
                {"device_code":"device-123","expires_in":600,"verification_url":"https://example.com/verify?code=abc"}
                """));

        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        AdminAuthorizationSession session = tool.startAdminAuthorization(new AdminAuthorizationRequest(
                List.of("calendar:calendar:readonly"),
                List.of(),
                false
        ));

        assertThat(session.deviceCode()).isEqualTo("device-123");
        assertThat(session.expiresIn()).isEqualTo(600);
        assertThat(session.verificationUrl()).isEqualTo("https://example.com/verify?code=abc");
        assertThat(session.qrCodePng()).isNotEmpty();
        assertThat(executor.recordedCommands().get(0).arguments())
                .containsExactly("auth", "login", "--json", "--no-wait", "--scope",
                        "calendar:calendar:readonly");
    }

    @Test
    void shouldWaitForAuthorizationCompletionByDeviceCode() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(0, """
                {"status":"authorized","profile":"default"}
                """));

        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        String result = tool.waitForAdminAuthorization(new AdminAuthorizationCompletionRequest("device-123"));

        assertThat(result).isEqualTo("{\"status\":\"authorized\",\"profile\":\"default\"}");
        assertThat(executor.recordedCommands().get(0).arguments())
                .containsExactly("auth", "login", "--json", "--device-code", "device-123");
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

        String result = tool.waitForAdminAuthorization(new AdminAuthorizationCompletionRequest("device-123"));

        assertThat(result).isEqualTo(
                "{\"event\":\"authorization_complete\",\"requested\":[\"calendar:calendar:readonly\"],\"user_open_id\":\"ou_123\"}"
        );
    }

    @Test
    void shouldPassRepeatableDomainsToCli() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        executor.enqueue(new CliCommandResult(0, """
                {"device_code":"device-456","expires_in":600,"verification_url":"https://example.com/verify?code=xyz"}
                """));

        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        byte[] qrCode = tool.getAuthQrCodePng(new AdminAuthorizationRequest(
                List.of(),
                List.of("calendar", "docs"),
                true
        ));

        assertThat(qrCode).isNotEmpty();
        assertThat(executor.recordedCommands().get(0).arguments())
                .containsExactly("auth", "login", "--json", "--no-wait", "--recommend", "--domain", "calendar",
                        "--domain", "docs");
    }

    @Test
    void shouldRejectRequestWithoutScopeOrDomain() {
        StubCliCommandExecutor executor = new StubCliCommandExecutor();
        LarkCliProperties properties = new LarkCliProperties();
        LarkCliClient client = new LarkCliClient(executor, properties, new ObjectMapper());
        LarkAdminAuthorizationTool tool = new LarkAdminAuthorizationTool(client, properties);

        assertThatThrownBy(() -> tool.getAuthQrCodePng(new AdminAuthorizationRequest(
                List.of(),
                List.of(),
                false
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Either scopes or domains must be provided");
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
