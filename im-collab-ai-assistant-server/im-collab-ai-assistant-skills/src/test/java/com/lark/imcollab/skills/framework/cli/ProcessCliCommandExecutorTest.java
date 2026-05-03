package com.lark.imcollab.skills.framework.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessCliCommandExecutorTest {

    @Test
    void windowsPs1ExecutableUsesPowerShellFileWrapper() {
        List<String> command = ProcessCliCommandExecutor.buildProcessCommand(
                "C:\\Users\\dev\\AppData\\Roaming\\npm\\lark-cli.ps1",
                List.of("docs", "+create"),
                "Windows 11"
        );

        assertThat(command).containsExactly(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                "C:\\Users\\dev\\AppData\\Roaming\\npm\\lark-cli.ps1",
                "docs",
                "+create"
        );
    }

    @Test
    void windowsCmdExecutableUsesCmdWrapper() {
        List<String> command = ProcessCliCommandExecutor.buildProcessCommand(
                "C:\\Users\\dev\\AppData\\Roaming\\npm\\lark-cli.cmd",
                List.of("docs", "+create"),
                "Windows 11"
        );

        assertThat(command).containsExactly(
                "cmd.exe",
                "/c",
                "C:\\Users\\dev\\AppData\\Roaming\\npm\\lark-cli.cmd",
                "docs",
                "+create"
        );
    }

    @Test
    void windowsBareExecutableUsesCmdWrapperForPathResolution() {
        List<String> command = ProcessCliCommandExecutor.buildProcessCommand(
                "lark-cli",
                List.of("docs", "+create"),
                "Windows Server 2022"
        );

        assertThat(command).containsExactly("cmd.exe", "/c", "lark-cli", "docs", "+create");
    }

    @Test
    void nonWindowsKeepsDirectExecution() {
        List<String> command = ProcessCliCommandExecutor.buildProcessCommand(
                "lark-cli",
                List.of("docs", "+create"),
                "Mac OS X"
        );

        assertThat(command).containsExactly("lark-cli", "docs", "+create");
    }
}
