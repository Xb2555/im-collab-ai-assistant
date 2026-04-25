package com.lark.imcollab.skills.framework.cli;

import java.util.List;

public record CliCommand(
        String executable,
        List<String> arguments,
        String workingDirectory
) {
}
