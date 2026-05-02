package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.databind.JsonNode;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LarkDocTool {

    private static final Pattern FLAG_PATTERN = Pattern.compile("(?<!\\S)--[a-zA-Z0-9-]+");
    private static final Set<String> CREATE_FALLBACK_FLAGS = Set.of(
            "--as", "--title", "--markdown", "--wiki-space", "--wiki-node", "--folder-token", "--profile"
    );
    private static final Set<String> FETCH_FALLBACK_FLAGS = Set.of(
            "--as", "--doc", "--format", "--limit", "--offset", "--profile"
    );

    private final LarkCliClient larkCliClient;
    private final LarkCliProperties properties;
    private final Map<String, Set<String>> supportedFlagCache = new ConcurrentHashMap<>();

    public LarkDocTool(LarkCliClient larkCliClient, LarkCliProperties properties) {
        this.larkCliClient = larkCliClient;
        this.properties = properties;
    }

    @Tool(description = "Scenario C: create a Lark doc from markdown.")
    public LarkDocCreateResult createDoc(String title, String markdown) {
        requireValue(title, "title");
        requireValue(markdown, "markdown");

        Set<String> supportedFlags = supportedFlags("+create");
        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+create");
        appendIfSupported(args, supportedFlags, "--as", resolveDocIdentity());
        appendIfSupported(args, supportedFlags, "--api-version", "v2");
        appendIfSupported(args, supportedFlags, "--title", title.trim());

        String normalizedMarkdown = normalizeMarkdown(title, markdown);
        if (supportedFlags.contains("--markdown")) {
            appendIfSupported(args, supportedFlags, "--markdown", normalizedMarkdown);
        } else if (supportedFlags.contains("--content")) {
            appendIfSupported(args, supportedFlags, "--doc-format", "markdown");
            appendIfSupported(args, supportedFlags, "--content", normalizedMarkdown);
        } else {
            throw new IllegalStateException("当前 lark-cli docs +create 不支持文档内容输入参数，请升级 lark-cli 后重试。");
        }
        JsonNode root = executeJson(args);
        JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
        JsonNode document = data.path("document").isMissingNode() ? data : data.path("document");
        return LarkDocCreateResult.builder()
                .docId(firstNonBlank(
                        text(document, "document_id"),
                        text(data, "doc_id"),
                        text(data, "document_id")
                ))
                .docUrl(firstNonBlank(
                        text(document, "url"),
                        text(data, "doc_url"),
                        text(data, "url")
                ))
                .message(firstNonBlank(
                        text(data, "message"),
                        text(root, "message")
                ))
                .build();
    }

    @Tool(description = "Scenario C: append markdown content to an existing Lark doc.")
    public LarkDocUpdateResult appendMarkdown(String docIdOrUrl, String markdown) {
        return updateDoc(docIdOrUrl, "append", markdown);
    }

    @Tool(description = "Scenario B/C: fetch readable content from a Lark doc by URL or token.")
    public LarkDocFetchResult fetchDoc(String docRef, String scope, String detail) {
        requireValue(docRef, "docRef");
        List<String> args = new ArrayList<>();
        Set<String> supportedFlags = supportedFlags("+fetch");
        args.add("docs");
        args.add("+fetch");
        appendIfSupported(args, supportedFlags, "--as", resolveDocIdentity());
        appendIfSupported(args, supportedFlags, "--api-version", "v2");
        if (!supportedFlags.contains("--doc")) {
            throw new IllegalStateException("当前 lark-cli docs +fetch 不支持 --doc 参数，请升级 lark-cli 后重试。");
        }
        appendIfSupported(args, supportedFlags, "--doc", docRef.trim());
        if (scope != null && !scope.isBlank()) {
            appendIfSupported(args, supportedFlags, "--scope", scope.trim());
        }
        if (detail != null && !detail.isBlank()) {
            appendIfSupported(args, supportedFlags, "--detail", detail.trim());
        }

        JsonNode root = executeJson(args);
        JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
        JsonNode document = data.path("document").isMissingNode() ? data : data.path("document");
        return LarkDocFetchResult.builder()
                .success(root.path("success").asBoolean(data.path("success").asBoolean(true)))
                .docRef(docRef.trim())
                .title(firstNonBlank(
                        text(data, "title"),
                        text(document, "title"),
                        text(root, "title")
                ))
                .content(firstNonBlank(
                        text(data, "content"),
                        text(document, "content"),
                        text(data, "markdown"),
                        text(document, "markdown"),
                        text(data, "xml"),
                        text(document, "xml"),
                        text(data, "text"),
                        text(document, "text"),
                        text(data, "raw_content"),
                        text(document, "raw_content"),
                        data.toString()
                ))
                .message(firstNonBlank(text(data, "message"), text(root, "message")))
                .build();
    }

    public LarkDocUpdateResult updateDoc(String docIdOrUrl, String mode, String markdown) {
        requireValue(docIdOrUrl, "docIdOrUrl");
        requireValue(mode, "mode");
        if (!"delete_range".equals(mode)) {
            requireValue(markdown, "markdown");
        }

        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+update");
        args.add("--as");
        args.add(resolveDocIdentity());
        args.add("--doc");
        args.add(docIdOrUrl.trim());
        args.add("--mode");
        args.add(mode.trim());
        if (markdown != null && !markdown.isBlank()) {
            args.add("--markdown");
            args.add(markdown);
        }

        JsonNode root = executeJson(args);
        JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
        List<String> boardTokens = new ArrayList<>();
        JsonNode boardTokensNode = data.path("board_tokens");
        if (boardTokensNode.isArray()) {
            boardTokensNode.forEach(node -> boardTokens.add(node.asText()));
        }
        return LarkDocUpdateResult.builder()
                .success(root.path("success").asBoolean(data.path("success").asBoolean(true)))
                .docId(text(data, "doc_id"))
                .mode(text(data, "mode"))
                .message(text(data, "message"))
                .boardTokens(boardTokens)
                .build();
    }

    private JsonNode executeJson(List<String> args) {
        CliCommandResult result = larkCliClient.execute(args);
        if (!result.isSuccess()) {
            throw new IllegalStateException(readableCliError(result.output()));
        }
        try {
            return larkCliClient.readJsonOutput(result.output());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse lark doc response", exception);
        }
    }

    private Set<String> supportedFlags(String docsCommand) {
        return supportedFlagCache.computeIfAbsent(docsCommand, command -> {
            CliCommandResult baseHelp = larkCliClient.execute(List.of("docs", command, "--help"));
            if (!baseHelp.isSuccess()) {
                return fallbackFlags(command);
            }
            Set<String> flags = parseFlags(baseHelp.output());
            if (flags.contains("--api-version")) {
                CliCommandResult versionedHelp = larkCliClient.execute(List.of(
                        "docs", command, "--api-version", "v2", "--help"
                ));
                if (versionedHelp.isSuccess()) {
                    Set<String> versionedFlags = parseFlags(versionedHelp.output());
                    if (!versionedFlags.isEmpty()) {
                        return versionedFlags;
                    }
                }
            }
            return flags.isEmpty() ? fallbackFlags(command) : flags;
        });
    }

    private Set<String> parseFlags(String helpOutput) {
        if (helpOutput == null || helpOutput.isBlank()) {
            return Set.of();
        }
        Set<String> flags = new LinkedHashSet<>();
        Matcher matcher = FLAG_PATTERN.matcher(helpOutput);
        while (matcher.find()) {
            flags.add(matcher.group());
        }
        return flags;
    }

    private Set<String> fallbackFlags(String docsCommand) {
        if ("+fetch".equals(docsCommand)) {
            return FETCH_FALLBACK_FLAGS;
        }
        if ("+create".equals(docsCommand)) {
            return CREATE_FALLBACK_FLAGS;
        }
        return Set.of("--as", "--profile");
    }

    private void appendIfSupported(List<String> args, Set<String> supportedFlags, String flag, String value) {
        if (!supportedFlags.contains(flag) || value == null || value.isBlank()) {
            return;
        }
        args.add(flag);
        args.add(value);
    }

    private String readableCliError(String output) {
        String extracted = larkCliClient.extractErrorMessage(output);
        if (extracted == null || extracted.isBlank()) {
            return "飞书文档工具调用失败，请稍后重试。";
        }
        String lower = extracted.toLowerCase(Locale.ROOT);
        if (lower.contains("unknown flag")) {
            String flag = extractUnknownFlag(extracted);
            return flag.isBlank()
                    ? "飞书文档工具参数不兼容，请检查 lark-cli 版本后重试。"
                    : "飞书文档工具参数不兼容：当前 lark-cli 不支持 " + flag + "。";
        }
        String firstLine = extracted.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("飞书文档工具调用失败，请稍后重试。");
        return firstLine.length() <= 220 ? firstLine : firstLine.substring(0, 220) + "...";
    }

    private String extractUnknownFlag(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        Matcher matcher = FLAG_PATTERN.matcher(message);
        return matcher.find() ? matcher.group() : "";
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        return field.isMissingNode() || field.isNull() ? "" : field.asText("");
    }

    private void requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
    }

    private String normalizeMarkdown(String title, String markdown) {
        String trimmed = markdown.trim();
        if (trimmed.startsWith("# ")) {
            return trimmed;
        }
        return "# " + title.trim() + "\n\n" + trimmed;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String resolveDocIdentity() {
        String identity = properties.getDocIdentity();
        return identity == null || identity.isBlank() ? "user" : identity.trim();
    }
}
