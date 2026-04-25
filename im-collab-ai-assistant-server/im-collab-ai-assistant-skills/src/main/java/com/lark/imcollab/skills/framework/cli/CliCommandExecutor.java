package com.lark.imcollab.skills.framework.cli;

import java.io.IOException;

public interface CliCommandExecutor {

    CliCommandResult execute(CliCommand command) throws IOException, InterruptedException;
}
