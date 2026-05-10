package com.lark.imcollab.skills.framework.cli;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
public class ProcessCliCommandExecutor implements CliCommandExecutor {

    @Override
    public CliCommandResult execute(CliCommand command) throws IOException, InterruptedException {
        List<String> shellCommand = buildProcessCommand(
                command.executable(),
                command.arguments(),
                System.getProperty("os.name", "")
        );

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
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        Thread stdoutReader = startStdoutReader(process.getInputStream(), outputBuffer);
        boolean completed;
        if (command.timeoutMillis() > 0) {
            completed = process.waitFor(command.timeoutMillis(), TimeUnit.MILLISECONDS);
        } else {
            process.waitFor();
            completed = true;
        }
        if (!completed) {
            process.destroyForcibly();
            stdoutReader.join(TimeUnit.SECONDS.toMillis(5));
            return new CliCommandResult(124, "lark-cli command timed out");
        }
        stdoutReader.join(TimeUnit.SECONDS.toMillis(5));
        byte[] outputBytes = outputBuffer.toByteArray();
        int exitCode = process.exitValue();
        String output = new String(outputBytes, StandardCharsets.UTF_8).trim();
        return new CliCommandResult(exitCode, output);
    }

    private Thread startStdoutReader(InputStream inputStream, ByteArrayOutputStream outputBuffer) {
        Thread reader = new Thread(() -> {
            try (InputStream in = inputStream; ByteArrayOutputStream out = outputBuffer) {
                in.transferTo(out);
            } catch (IOException ignored) {
                // Best-effort read only. The process exit code remains the source of truth.
            }
        }, "cli-stdout-reader");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    static List<String> buildProcessCommand(String executable, List<String> arguments, String osName) {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("CLI executable must not be blank");
        }

        String normalizedExecutable = executable.trim();
        List<String> command = new ArrayList<>();
        if (isWindows(osName)) {
            String lowerExecutable = normalizedExecutable.toLowerCase(Locale.ROOT);
            if (lowerExecutable.endsWith(".ps1")) {
                command.add("powershell.exe");
                command.add("-NoProfile");
                command.add("-ExecutionPolicy");
                command.add("Bypass");
                command.add("-File");
                command.add(normalizedExecutable);
                command.addAll(arguments == null ? List.of() : arguments);
                return command;
            }
            if (lowerExecutable.endsWith(".cmd") || lowerExecutable.endsWith(".bat") || !hasExecutableExtension(lowerExecutable)) {
                command.add("cmd.exe");
                command.add("/c");
                command.add(normalizedExecutable);
                command.addAll(arguments == null ? List.of() : arguments);
                return command;
            }
        }

        command.add(normalizedExecutable);
        command.addAll(arguments == null ? List.of() : arguments);
        return command;
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean hasExecutableExtension(String executable) {
        return executable.endsWith(".exe")
                || executable.endsWith(".com")
                || executable.endsWith(".cmd")
                || executable.endsWith(".bat")
                || executable.endsWith(".ps1");
    }
}
