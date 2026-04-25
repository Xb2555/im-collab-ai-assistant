package com.lark.imcollab.skills.lark.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliCommandExecutor;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

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
        try {
            return cliCommandExecutor.execute(new CliCommand(
                    properties.getExecutable(),
                    args,
                    normalizeWorkingDirectory(properties.getWorkingDirectory())
            ));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("lark-cli execution interrupted", exception);
        }
    }

    public JsonNode executeJson(List<String> args) {
        CliCommandResult result = execute(args);
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

    private JsonNode readJsonOutput(String output) throws IOException {
        try {
            return objectMapper.readTree(output);
        } catch (IOException exception) {
            String jsonPayload = extractFirstJsonPayload(output);
            if (jsonPayload == null) {
                throw exception;
            }
            return objectMapper.readTree(jsonPayload);
        }
    }

    private String extractFirstJsonPayload(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }

        for (int index = 0; index < output.length(); index++) {
            char start = output.charAt(index);
            if (start != '{' && start != '[') {
                continue;
            }

            int end = findJsonPayloadEnd(output, index, start == '{' ? '}' : ']');
            if (end != -1) {
                return output.substring(index, end + 1);
            }
        }
        return null;
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
}
