package com.lark.imcollab.skills.framework.cli;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
        byte[] outputBytes = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        String output = new String(outputBytes, StandardCharsets.UTF_8).trim();
        return new CliCommandResult(exitCode, output);
    }
}
