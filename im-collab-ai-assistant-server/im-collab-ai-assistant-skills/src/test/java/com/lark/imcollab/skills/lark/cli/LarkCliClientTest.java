package com.lark.imcollab.skills.lark.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliCommandExecutor;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LarkCliClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsPowerShellPs1WrapperOnWindowsWhenCommandRequiresStdin() throws Exception {
        String originalOs = System.getProperty("os.name");
        Path npmDir = Files.createTempDirectory("lark-cli-client-test");
        Path ps1 = npmDir.resolve("lark-cli.ps1");
        Path cmd = npmDir.resolve("lark-cli.cmd");
        Files.writeString(ps1, "placeholder");
        Files.writeString(cmd, "placeholder");

        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(0, "{\"ok\":true}");
        };

        try {
            System.setProperty("os.name", "Windows 11");
            LarkCliProperties properties = new LarkCliProperties();
            properties.setExecutable("powershell");
            properties.setArgs(List.of("-ExecutionPolicy", "Bypass", "-File", ps1.toString()));

            LarkCliClient client = new LarkCliClient(executor, properties, objectMapper);
            client.execute(List.of("slides", "+create"), "stdin-payload", 1234L);

            assertThat(commands).hasSize(1);
            assertThat(commands.get(0).executable()).isEqualTo(ps1.toString());
            assertThat(commands.get(0).arguments()).containsExactly("slides", "+create");
            assertThat(commands.get(0).stdin()).isEqualTo("stdin-payload");
            assertThat(commands.get(0).timeoutMillis()).isEqualTo(1234L);
        } finally {
            if (originalOs == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", originalOs);
            }
            Files.deleteIfExists(ps1);
            Files.deleteIfExists(cmd);
            Files.deleteIfExists(npmDir);
        }
    }

    @Test
    void rewritesPowerShellPs1WrapperToCmdShimOnWindowsWhenCommandDoesNotRequireStdin() throws Exception {
        String originalOs = System.getProperty("os.name");
        Path npmDir = Files.createTempDirectory("lark-cli-client-test");
        Path ps1 = npmDir.resolve("lark-cli.ps1");
        Path cmd = npmDir.resolve("lark-cli.cmd");
        Files.writeString(ps1, "placeholder");
        Files.writeString(cmd, "placeholder");

        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(0, "{\"ok\":true}");
        };

        try {
            System.setProperty("os.name", "Windows 11");
            LarkCliProperties properties = new LarkCliProperties();
            properties.setExecutable("powershell");
            properties.setArgs(List.of("-ExecutionPolicy", "Bypass", "-File", ps1.toString()));

            LarkCliClient client = new LarkCliClient(executor, properties, objectMapper);
            client.execute(List.of("docs", "+create"), null, 1234L);

            assertThat(commands).hasSize(1);
            assertThat(commands.get(0).executable()).isEqualTo(cmd.toString());
            assertThat(commands.get(0).arguments()).containsExactly("docs", "+create");
            assertThat(commands.get(0).stdin()).isNull();
            assertThat(commands.get(0).timeoutMillis()).isEqualTo(1234L);
        } finally {
            if (originalOs == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", originalOs);
            }
            Files.deleteIfExists(ps1);
            Files.deleteIfExists(cmd);
            Files.deleteIfExists(npmDir);
        }
    }
}
