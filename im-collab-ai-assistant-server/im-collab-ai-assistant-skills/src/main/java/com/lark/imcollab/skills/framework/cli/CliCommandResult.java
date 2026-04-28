package com.lark.imcollab.skills.framework.cli;

public record CliCommandResult(
        int exitCode,
        String output
) {

    public boolean isSuccess() {
        return exitCode == 0;
    }
}
