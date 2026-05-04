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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
            return cliCommandExecutor.execute(new CliCommand(
                    properties.getExecutable(),
                    fullArgs,
                    normalizeWorkingDirectory(properties.getWorkingDirectory()),
                    stdin,
                    timeoutMillis
            ));
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

    private CliExecutable resolveExecutable(boolean requiresStdin) {
        String executable = properties.getExecutable();
        List<String> args = properties.getArgs();
        if (args == null || args.isEmpty() || !isWindows()) {
            return new CliExecutable(executable, args == null ? List.of() : args);
        }
        if (!isPowerShell(executable) || args.size() < 3) {
            return new CliExecutable(executable, args);
        }
        int fileIndex = findPowerShellFileArg(args);
        if (fileIndex < 0 || fileIndex >= args.size() - 1) {
            return new CliExecutable(executable, args);
        }
        String ps1Path = args.get(fileIndex + 1);
        if (ps1Path == null || !ps1Path.toLowerCase(Locale.ROOT).endsWith(".ps1")) {
            return new CliExecutable(executable, args);
        }
        if (requiresStdin) {
            return new CliExecutable(ps1Path, List.of());
        }
        String cmdPath = ps1Path.substring(0, ps1Path.length() - 4) + ".cmd";
        if (Files.exists(Path.of(cmdPath))) {
            return new CliExecutable(cmdPath, List.of());
        }
        return new CliExecutable(ps1Path, List.of());
    }

    private int findPowerShellFileArg(List<String> args) {
        for (int index = 0; index < args.size(); index++) {
            String arg = args.get(index);
            if ("-File".equalsIgnoreCase(arg)) {
                return index;
            }
        }
        return -1;
    }

    private boolean isPowerShell(String executable) {
        if (executable == null) {
            return false;
        }
        String normalized = executable.toLowerCase(Locale.ROOT);
        return normalized.endsWith("powershell")
                || normalized.endsWith("powershell.exe")
                || normalized.endsWith("pwsh")
                || normalized.endsWith("pwsh.exe");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private record CliExecutable(String executable, List<String> prefixArgs) {
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
        if (isPowerShellWrapper(normalizedExecutable, normalizedArgs)) {
            String scriptPath = normalizedArgs.get(normalizedArgs.size() - 1).trim();
            Path cmdPath = cmdSibling(scriptPath);
            if (Files.exists(cmdPath)) {
                log.info("Rewriting Lark CLI invocation from PowerShell wrapper to cmd shim: ps1='{}', cmd='{}'", scriptPath, cmdPath);
                return new ResolvedCliCommand(cmdPath.toString(), List.of());
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

    private record ResolvedCliCommand(String executable, List<String> args) {
    }
}
