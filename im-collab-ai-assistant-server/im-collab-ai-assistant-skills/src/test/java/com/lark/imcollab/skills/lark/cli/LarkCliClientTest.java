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
    void rewritesPowerShellPs1WrapperToCmdShimOnWindows() throws Exception {
        String originalOs = System.getProperty("os.name");
        Path tempDir = Files.createTempDirectory("lark-cli-client");
        Path ps1 = tempDir.resolve("lark-cli.ps1");
        Path cmd = tempDir.resolve("lark-cli.cmd");
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

            client.execute(List.of("docs", "+update"), "stdin", 1234L);

            assertThat(commands).hasSize(1);
            assertThat(commands.get(0).executable()).isEqualTo(cmd.toString());
            assertThat(commands.get(0).arguments()).containsExactly("docs", "+update");
            assertThat(commands.get(0).stdin()).isEqualTo("stdin");
            assertThat(commands.get(0).timeoutMillis()).isEqualTo(1234L);
        } finally {
            if (originalOs == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", originalOs);
            }
            Files.deleteIfExists(ps1);
            Files.deleteIfExists(cmd);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    void fallsBackToPs1WhenCmdShimIsMissing() throws Exception {
        String originalOs = System.getProperty("os.name");
        Path tempDir = Files.createTempDirectory("lark-cli-client");
        Path ps1 = tempDir.resolve("lark-cli.ps1");
        Files.writeString(ps1, "placeholder");

        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(0, "{\"ok\":true}");
        };

        try {
            System.setProperty("os.name", "Windows 11");
            LarkCliProperties properties = new LarkCliProperties();
            properties.setExecutable("powershell.exe");
            properties.setArgs(List.of("-NoProfile", "-File", ps1.toString()));
            LarkCliClient client = new LarkCliClient(executor, properties, objectMapper);

            client.execute(List.of("slides", "+create"), null, 4321L);

            assertThat(commands).hasSize(1);
            assertThat(commands.get(0).executable()).isEqualTo("powershell.exe");
            assertThat(commands.get(0).arguments()).containsExactly("-NoProfile", "-File", ps1.toString(), "slides", "+create");
            assertThat(commands.get(0).timeoutMillis()).isEqualTo(4321L);
        } finally {
            if (originalOs == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", originalOs);
            }
            Files.deleteIfExists(ps1);
            Files.deleteIfExists(tempDir);
        }
    }
}
