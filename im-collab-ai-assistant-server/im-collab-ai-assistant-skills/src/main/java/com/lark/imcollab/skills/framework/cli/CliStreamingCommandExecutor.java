package com.lark.imcollab.skills.framework.cli;

import java.io.IOException;

public interface CliStreamingCommandExecutor {

    CliStreamHandle start(CliCommand command, CliStreamListener listener) throws IOException;
}
