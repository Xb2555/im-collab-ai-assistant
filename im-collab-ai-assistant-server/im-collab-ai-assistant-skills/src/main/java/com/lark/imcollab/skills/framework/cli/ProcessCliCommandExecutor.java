package com.lark.imcollab.skills.framework.cli;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessCliCommandExecutor implements CliCommandExecutor {

    @Override
    public CliCommandResult execute(CliCommand command) throws IOException, InterruptedException {
        List<String> shellCommand = new ArrayList<>();
        shellCommand.add(command.executable());
        shellCommand.addAll(command.arguments());

        ProcessBuilder processBuilder = new ProcessBuilder(shellCommand);
        processBuilder.redirectErrorStream(true);
        if (command.workingDirectory() != null && !command.workingDirectory().isBlank()) {
            processBuilder.directory(new File(command.workingDirectory()));
        }

        Process process = processBuilder.start();
        if (command.stdin() != null) {
            process.getOutputStream().write(command.stdin().getBytes(StandardCharsets.UTF_8));
        }
        process.getOutputStream().close();
        boolean completed;
        if (command.timeoutMillis() > 0) {
            completed = process.waitFor(command.timeoutMillis(), TimeUnit.MILLISECONDS);
        } else {
            process.waitFor();
            completed = true;
        }
        if (!completed) {
            process.destroyForcibly();
            return new CliCommandResult(124, "lark-cli command timed out");
        }
        byte[] outputBytes = process.getInputStream().readAllBytes();
        int exitCode = process.exitValue();
        String output = new String(outputBytes, StandardCharsets.UTF_8).trim();
        return new CliCommandResult(exitCode, output);
    }
}
