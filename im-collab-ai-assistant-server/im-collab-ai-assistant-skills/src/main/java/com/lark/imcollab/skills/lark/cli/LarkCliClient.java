package com.lark.imcollab.skills.lark.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliCommandExecutor;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Component
public class LarkCliClient {

    private final CliCommandExecutor cliCommandExecutor;
    private final LarkCliProperties properties;
    private final ObjectMapper objectMapper;

    public LarkCliClient(
            CliCommandExecutor cliCommandExecutor,
            LarkCliProperties properties,
            ObjectMapper objectMapper
    ) {
        this.cliCommandExecutor = cliCommandExecutor;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public CliCommandResult execute(List<String> args) {
        return execute(args, null);
    }

    public CliCommandResult execute(List<String> args, String stdin) {
        return execute(args, stdin, 0);
    }

    public CliCommandResult execute(List<String> args, String stdin, long timeoutMillis) {
        try {
            List<String> fullArgs = new ArrayList<>(properties.getArgs());
            fullArgs.addAll(args);
            ResolvedCliCommand resolvedCommand = resolveCommand(properties.getExecutable(), fullArgs);

            CliCommandResult result = cliCommandExecutor.execute(new CliCommand(
                    resolvedCommand.executable(),
                    resolvedCommand.args(),
                    normalizeWorkingDirectory(properties.getWorkingDirectory()),
                    stdin,
                    timeoutMillis
            ));

            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("lark-cli execution interrupted", exception);
        }
    }

    public JsonNode executeJson(List<String> args) {
        return executeJson(args, null);
    }

    public JsonNode executeJson(List<String> args, String stdin) {
        return executeJson(args, stdin, 0);
    }

    public JsonNode executeJson(List<String> args, String stdin, long timeoutMillis) {
        CliCommandResult result = execute(args, stdin, timeoutMillis);
        if (!result.isSuccess()) {
            throw new IllegalStateException(extractErrorMessage(result.output()));
        }
        try {
            return readJsonOutput(result.output());
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to parse lark-cli JSON output", exception);
        }
    }

    public String extractErrorMessage(String output) {
        try {
            JsonNode root = readJsonOutput(output);
            JsonNode errorNode = root.path("error").path("message");
            if (!errorNode.isMissingNode() && !errorNode.isNull() && !errorNode.asText().isBlank()) {
                return errorNode.asText();
            }
        } catch (IOException ignored) {
            // Fall back to raw output.
        }
        return output == null || output.isBlank() ? "Unknown lark-cli error" : output;
    }

    private String normalizeWorkingDirectory(String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return null;
        }
        return workingDirectory.trim();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public JsonNode readJsonOutput(String output) throws IOException {
        try {
            return objectMapper.readTree(output);
        } catch (IOException exception) {
            return readFirstJsonPayload(output, exception);
        }
    }

    private JsonNode readFirstJsonPayload(String output, IOException originalException) throws IOException {
        if (output == null || output.isBlank()) {
            throw originalException;
        }

        IOException lastException = originalException;
        for (int index = 0; index < output.length(); index++) {
            char start = output.charAt(index);
            if (start != '{' && start != '[') {
                continue;
            }

            int end = findJsonPayloadEnd(output, index, start == '{' ? '}' : ']');
            if (end != -1) {
                try {
                    return objectMapper.readTree(output.substring(index, end + 1));
                } catch (IOException exception) {
                    lastException = exception;
                }
            }
        }
        throw lastException;
    }

    private int findJsonPayloadEnd(String output, int startIndex, char expectedEnd) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int index = startIndex; index < output.length(); index++) {
            char current = output.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
            } else if (current == output.charAt(startIndex)) {
                depth++;
            } else if (current == expectedEnd) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private ResolvedCliCommand resolveCommand(String executable, List<String> configuredArgs) {
        String normalizedExecutable = executable == null ? null : executable.trim();
        List<String> normalizedArgs = configuredArgs == null ? List.of() : new ArrayList<>(configuredArgs);
        if (!isWindows()) {
            return new ResolvedCliCommand(normalizedExecutable, normalizedArgs);
        }
        if (isWindowsCmdShim(normalizedExecutable)) {
            Path runJs = runJsSibling(normalizedExecutable);
            if (Files.exists(runJs)) {
                String nodeExecutable = resolveNodeExecutable(normalizedExecutable);
                List<String> directArgs = new ArrayList<>();
                directArgs.add(runJs.toString());
                directArgs.addAll(normalizedArgs);

                return new ResolvedCliCommand(nodeExecutable, directArgs);
            }
        }
        if (isPowerShellWrapper(normalizedExecutable, normalizedArgs)) {
            String scriptPath = normalizedArgs.get(normalizedArgs.size() - 1).trim();
            Path cmdPath = cmdSibling(scriptPath);
            if (Files.exists(cmdPath)) {
                log.info("Rewriting Lark CLI invocation from PowerShell wrapper to cmd shim: ps1='{}', cmd='{}'", scriptPath, cmdPath);
                return new ResolvedCliCommand(cmdPath.toString(), List.of());
            }
        }
        if (shouldUsePowerShellShim(normalizedExecutable, normalizedArgs)) {
            Path ps1Path = ps1Sibling(normalizedExecutable);
            if (Files.exists(ps1Path)) {
                log.info("Rewriting Lark CLI invocation from cmd shim to PowerShell wrapper for special characters: cmd='{}', ps1='{}'",
                        normalizedExecutable, ps1Path);
                return new ResolvedCliCommand("powershell.exe", buildPowerShellFileArgs(ps1Path.toString(), normalizedArgs));
            }
        }
        return new ResolvedCliCommand(normalizedExecutable, normalizedArgs);
    }

    private boolean isPowerShellWrapper(String executable, List<String> args) {
        if (executable == null || args == null || args.size() < 2) {
            return false;
        }
        String normalizedExecutable = executable.toLowerCase(Locale.ROOT);
        if (!normalizedExecutable.endsWith("powershell")
                && !normalizedExecutable.endsWith("powershell.exe")
                && !normalizedExecutable.endsWith("pwsh")
                && !normalizedExecutable.endsWith("pwsh.exe")) {
            return false;
        }
        return "-file".equalsIgnoreCase(args.get(args.size() - 2))
                && args.get(args.size() - 1) != null
                && args.get(args.size() - 1).toLowerCase(Locale.ROOT).endsWith(".ps1");
    }

    private Path cmdSibling(String ps1Path) {
        Path ps1 = Paths.get(ps1Path);
        String fileName = ps1.getFileName() == null ? ps1Path : ps1.getFileName().toString();
        String cmdName = fileName.substring(0, fileName.length() - 4) + ".cmd";
        return ps1.resolveSibling(cmdName);
    }

    private Path ps1Sibling(String cmdPath) {
        Path cmd = Paths.get(cmdPath);
        String fileName = cmd.getFileName() == null ? cmdPath : cmd.getFileName().toString();
        String ps1Name = fileName.substring(0, fileName.length() - 4) + ".ps1";
        return cmd.resolveSibling(ps1Name);
    }

    private Path runJsSibling(String cmdPath) {
        Path cmd = Paths.get(cmdPath).toAbsolutePath().normalize();
        return cmd.getParent()
                .resolve("node_modules")
                .resolve("@larksuite")
                .resolve("cli")
                .resolve("scripts")
                .resolve("run.js");
    }

    private boolean isWindowsCmdShim(String executable) {
        if (executable == null) {
            return false;
        }
        String lower = executable.toLowerCase(Locale.ROOT);
        return lower.endsWith("lark-cli.cmd");
    }

    private String resolveNodeExecutable(String cmdPath) {
        Path cmd = Paths.get(cmdPath).toAbsolutePath().normalize();
        Path bundledNode = cmd.getParent().resolve("node.exe");
        if (Files.exists(bundledNode)) {
            return bundledNode.toString();
        }
        return resolveNodeFromPath().orElse("node.exe");
    }

    private Optional<String> resolveNodeFromPath() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        String[] segments = pathEnv.split(java.io.File.pathSeparator);
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            Path candidate = Paths.get(segment.trim()).resolve("node.exe");
            if (Files.exists(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize().toString());
            }
        }
        return Optional.empty();
    }

    private boolean shouldUsePowerShellShim(String executable, List<String> args) {
        if (executable == null || args == null) {
            return false;
        }
        String lowerExecutable = executable.toLowerCase(Locale.ROOT);
        if (!lowerExecutable.endsWith(".cmd")) {
            return false;
        }
        return args.stream().filter(arg -> arg != null).anyMatch(arg ->
                arg.contains("<")
                        || arg.contains(">")
                        || arg.contains("\n")
                        || arg.contains("\r"));
    }

    private List<String> buildPowerShellFileArgs(String scriptPath, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(scriptPath);
        command.addAll(args == null ? List.of() : args);
        return command;
    }

    private List<String> summarizeArgs(List<String> args) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        List<String> summary = new ArrayList<>(args.size());
        for (String arg : args) {
            if (arg == null) {
                summary.add(null);
                continue;
            }
            if (arg.startsWith("@")) {
                summary.add(arg);
                continue;
            }
            if (arg.length() > 160) {
                summary.add(arg.substring(0, 157) + "...");
                continue;
            }
            summary.add(arg);
        }
        return summary;
    }

    private record ResolvedCliCommand(String executable, List<String> args) {
    }
}
