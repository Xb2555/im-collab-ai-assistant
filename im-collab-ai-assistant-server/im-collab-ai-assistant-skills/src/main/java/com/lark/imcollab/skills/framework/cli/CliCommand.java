package com.lark.imcollab.skills.framework.cli;

import java.util.List;

public record CliCommand(
        String executable,
        List<String> arguments,
        String workingDirectory,
        String stdin,
        long timeoutMillis
) {

    public CliCommand(String executable, List<String> arguments, String workingDirectory, String stdin) {
        this(executable, arguments, workingDirectory, stdin, 0);
    }

    public CliCommand(String executable, List<String> arguments, String workingDirectory) {
        this(executable, arguments, workingDirectory, null, 0);
    }
}
