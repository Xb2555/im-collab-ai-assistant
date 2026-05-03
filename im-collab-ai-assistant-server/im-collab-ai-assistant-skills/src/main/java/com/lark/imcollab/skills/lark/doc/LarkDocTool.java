package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.databind.JsonNode;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LarkDocTool {

    private static final Pattern DOC_URL_PATTERN = Pattern.compile("/(?:docx|wiki)/([A-Za-z0-9]+)");

    private final LarkCliClient larkCliClient;
    private final LarkCliProperties properties;
    private final Map<String, Set<String>> supportedFlagCache = new ConcurrentHashMap<>();

    private static final Pattern FLAG_PATTERN = Pattern.compile("(?<!\\S)--[a-zA-Z0-9-]+");
    private static final Set<String> CREATE_FALLBACK_FLAGS = Set.of(
            "--as", "--title", "--markdown", "--wiki-space", "--wiki-node", "--folder-token", "--profile"
    );
    private static final Set<String> FETCH_FALLBACK_FLAGS = Set.of(
            "--as", "--doc", "--format", "--limit", "--offset", "--profile"
    );


    public LarkDocTool(LarkCliClient larkCliClient, LarkCliProperties properties) {
        this.larkCliClient = larkCliClient;
        this.properties = properties;
    }

    @Tool(description = "Scenario C: create a Lark doc from markdown.")
    public LarkDocCreateResult createDoc(String title, String markdown) {
        requireValue(title, "title");
        requireValue(markdown, "markdown");

        Set<String> supportedFlags = supportedFlags("+create");
        String normalizedMarkdown = normalizeMarkdown(title, markdown);
        JsonNode root = executeJsonWithCompat("+create", createDocAttempts(title.trim(), normalizedMarkdown, supportedFlags));
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
        Set<String> supportedFlags = supportedFlags("+fetch");
        if (!supportedFlags.contains("--doc")) {
            throw new IllegalStateException("当前 lark-cli docs +fetch 不支持 --doc 参数，请升级 lark-cli 后重试。");
        }
        JsonNode root = executeJsonWithCompat("+fetch", fetchDocAttempts(docRef.trim(), scope, detail, supportedFlags));
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
        if (!"delete_range".equals(mode) && !"block_delete".equals(mode)) {
            requireValue(markdown, "markdown");
        }

        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+update");
        args.add("--api-version");
        args.add("v2");
        args.add("--as");
        args.add(resolveDocIdentity());
        args.add("--doc");
        args.add(docIdOrUrl.trim());
        args.add("--command");
        args.add(mode.trim());
        if (markdown != null && !markdown.isBlank()) {
            args.add("--doc-format");
            args.add("markdown");
            args.add("--content");
            args.add(markdown);
        }

        return parseUpdateResult(executeJson(args), mode);
    }

    public LarkDocFetchResult fetchDocOutline(String docIdOrUrl) {
        return fetchDoc(docIdOrUrl, "outline", "xml", "with-ids", null, null, null);
    }

    public LarkDocFetchResult fetchDocSection(String docIdOrUrl, String startBlockId, String detail) {
        requireValue(startBlockId, "startBlockId");
        return fetchDoc(docIdOrUrl, "section", "xml", defaultIfBlank(detail, "full"), startBlockId, null, null);
    }

    public LarkDocFetchResult fetchDocFull(String docIdOrUrl, String detail) {
        return fetchDoc(docIdOrUrl, null, "xml", defaultIfBlank(detail, "full"), null, null, null);
    }

    public LarkDocFetchResult fetchDocByKeyword(String docIdOrUrl, String keyword, String detail) {
        requireValue(keyword, "keyword");
        return fetchDoc(docIdOrUrl, "keyword", "xml", defaultIfBlank(detail, "with-ids"), null, null, keyword);
    }

    public LarkDocFetchResult fetchDocSectionMarkdown(String docIdOrUrl, String startBlockId) {
        requireValue(startBlockId, "startBlockId");
        return fetchDoc(docIdOrUrl, "section", "markdown", "simple", startBlockId, null, null);
    }

    public LarkDocFetchResult fetchDocRangeMarkdown(String docIdOrUrl, String startBlockId, String endBlockId) {
        requireValue(startBlockId, "startBlockId");
        requireValue(endBlockId, "endBlockId");
        return fetchDoc(docIdOrUrl, "range", "markdown", "simple", startBlockId, endBlockId, null);
    }

    public LarkDocFetchResult fetchDocFullMarkdown(String docIdOrUrl) {
        return fetchDoc(docIdOrUrl, null, "markdown", "simple", null, null, null);
    }

    public LarkDocUpdateResult updateByCommand(
            String docIdOrUrl,
            String command,
            String content,
            String docFormat,
            String blockId,
            String pattern,
            Long revisionId
    ) {
        requireValue(docIdOrUrl, "docIdOrUrl");
        requireValue(command, "command");

        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+update");
        args.add("--api-version");
        args.add("v2");
        args.add("--as");
        args.add(resolveDocIdentity());
        args.add("--doc");
        args.add(docIdOrUrl.trim());
        args.add("--command");
        args.add(command.trim());
        if (hasText(docFormat)) {
            args.add("--doc-format");
            args.add(docFormat.trim());
        }
        if (content != null) {
            args.add("--content");
            args.add(content);
        }
        if (hasText(blockId)) {
            args.add("--block-id");
            args.add(blockId.trim());
        }
        if (hasText(pattern)) {
            args.add("--pattern");
            args.add(pattern);
        }
        if (revisionId != null && revisionId >= 0) {
            args.add("--revision-id");
            args.add(String.valueOf(revisionId));
        }
        return parseUpdateResult(executeJson(args), command);
    }

    public String extractDocumentId(String docIdOrUrl) {
        requireValue(docIdOrUrl, "docIdOrUrl");
        String trimmed = docIdOrUrl.trim();
        Matcher matcher = DOC_URL_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return trimmed;
    }

    private JsonNode executeJson(List<String> args) {
        CliCommandResult result = larkCliClient.execute(args, null, commandTimeoutMillis());
        if (!result.isSuccess()) {
            throw new IllegalStateException(readableCliError(result.output()));
        }
        try {
            return larkCliClient.readJsonOutput(result.output());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse lark doc response", exception);
        }
    }

    private JsonNode executeJsonWithCompat(String docsCommand, List<List<String>> attempts) {
        IllegalStateException lastError = null;
        for (int index = 0; index < attempts.size(); index++) {
            List<String> args = attempts.get(index);
            CliCommandResult result = larkCliClient.execute(args, null, commandTimeoutMillis());
            if (result.isSuccess()) {
                try {
                    return larkCliClient.readJsonOutput(result.output());
                } catch (Exception exception) {
                    throw new IllegalStateException("Failed to parse lark doc response", exception);
                }
            }
            String output = result.output();
            String message = readableCliError(output);
            lastError = new IllegalStateException(message);
            if (!isUnknownFlagError(output) || index == attempts.size() - 1) {
                throw lastError;
            }
            supportedFlagCache.remove(docsCommand);
            log.warn(
                    "Lark docs command hit flag incompatibility, retrying with fallback: command={}, attempt={}, executable={}, cliVersion={}, args={}, error={}",
                    docsCommand,
                    index + 1,
                    properties.getExecutable(),
                    cliVersionSafe(),
                    args,
                    message
            );
        }
        throw lastError == null ? new IllegalStateException("飞书文档工具调用失败，请稍后重试。") : lastError;
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

    private List<List<String>> createDocAttempts(String title, String markdown, Set<String> supportedFlags) {
        Map<String, List<String>> attempts = new LinkedHashMap<>();
        attempts.put("preferred", createDocArgs(title, markdown, supportedFlags, true, false));
        attempts.put("no-api-version", createDocArgs(title, markdown, supportedFlags, false, false));
        attempts.put("classic-v1", classicCreateDocArgs(title, markdown));
        return distinctAttempts(attempts);
    }

    private List<List<String>> fetchDocAttempts(String docRef, String scope, String detail, Set<String> supportedFlags) {
        Map<String, List<String>> attempts = new LinkedHashMap<>();
        attempts.put("preferred", fetchDocArgs(docRef, scope, detail, supportedFlags, true, true));
        attempts.put("no-api-version", fetchDocArgs(docRef, scope, detail, supportedFlags, false, true));
        attempts.put("basic", fetchDocArgs(docRef, null, null, Set.of("--as", "--doc"), false, false));
        return distinctAttempts(attempts);
    }

    private List<String> createDocArgs(
            String title,
            String markdown,
            Set<String> supportedFlags,
            boolean withApiVersion,
            boolean strictContentCheck
    ) {
        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+create");
        appendIfSupported(args, supportedFlags, "--as", resolveDocIdentity());
        if (withApiVersion) {
            appendIfSupported(args, supportedFlags, "--api-version", "v2");
        }
        appendIfSupported(args, supportedFlags, "--title", title);
        if (supportedFlags.contains("--markdown")) {
            appendIfSupported(args, supportedFlags, "--markdown", markdown);
            return args;
        }
        if (supportedFlags.contains("--content")) {
            appendIfSupported(args, supportedFlags, "--doc-format", "markdown");
            appendIfSupported(args, supportedFlags, "--content", markdown);
            return args;
        }
        if (strictContentCheck) {
            throw new IllegalStateException("当前 lark-cli docs +create 不支持文档内容输入参数，请升级 lark-cli 后重试。");
        }
        return args;
    }

    private List<String> classicCreateDocArgs(String title, String markdown) {
        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+create");
        args.add("--as");
        args.add(resolveDocIdentity());
        args.add("--title");
        args.add(title);
        args.add("--markdown");
        args.add(markdown);
        return args;
    }

    private List<String> fetchDocArgs(
            String docRef,
            String scope,
            String detail,
            Set<String> supportedFlags,
            boolean withApiVersion,
            boolean includeContextFlags
    ) {
        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+fetch");
        appendIfSupported(args, supportedFlags, "--as", resolveDocIdentity());
        if (withApiVersion) {
            appendIfSupported(args, supportedFlags, "--api-version", "v2");
        }
        appendIfSupported(args, supportedFlags, "--doc", docRef);
        if (includeContextFlags && scope != null && !scope.isBlank()) {
            appendIfSupported(args, supportedFlags, "--scope", scope.trim());
        }
        if (includeContextFlags && detail != null && !detail.isBlank()) {
            appendIfSupported(args, supportedFlags, "--detail", detail.trim());
        }
        return args;
    }

    private List<List<String>> distinctAttempts(Map<String, List<String>> attempts) {
        List<List<String>> result = new ArrayList<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        for (List<String> args : attempts.values()) {
            if (args == null || args.isEmpty()) {
                continue;
            }
            String fingerprint = String.join("\u0000", args);
            if (fingerprints.add(fingerprint)) {
                result.add(args);
            }
        }
        return result;
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
        if (isCliRuntimeError(lower)) {
            return "飞书文档创建失败，请检查 lark-cli 可执行配置、登录状态或文档权限后重试。";
        }
        String firstLine = extracted.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("飞书文档工具调用失败，请稍后重试。");
        return firstLine.length() <= 220 ? firstLine : firstLine.substring(0, 220) + "...";
    }

    private boolean isCliRuntimeError(String lowerMessage) {
        return lowerMessage.contains("powershell")
                || lowerMessage.contains(".ps1")
                || lowerMessage.contains("parameterbinding")
                || lowerMessage.contains("processbuilder")
                || lowerMessage.contains("java.lang.")
                || lowerMessage.contains("org.springframework.")
                || lowerMessage.contains("exception:");
    }

    private String extractUnknownFlag(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        Matcher matcher = FLAG_PATTERN.matcher(message);
        return matcher.find() ? matcher.group() : "";
    }

    private boolean isUnknownFlagError(String output) {
        String extracted = larkCliClient.extractErrorMessage(output);
        return extracted != null && extracted.toLowerCase(Locale.ROOT).contains("unknown flag");
    }

    private String cliVersionSafe() {
        try {
            CliCommandResult result = larkCliClient.execute(List.of("--version"));
            return result.output();
        } catch (RuntimeException exception) {
            return "unavailable:" + exception.getMessage();
        }
    }

    private long commandTimeoutMillis() {
        int timeoutSeconds = properties.getTimeoutSeconds();
        return timeoutSeconds <= 0 ? 0 : timeoutSeconds * 1000L;
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        return field.isMissingNode() || field.isNull() ? "" : field.asText("");
    }

    private LarkDocFetchResult fetchDoc(
            String docIdOrUrl,
            String scope,
            String docFormat,
            String detail,
            String startBlockId,
            String endBlockId,
            String keyword
    ) {
        requireValue(docIdOrUrl, "docIdOrUrl");
        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+fetch");
        args.add("--api-version");
        args.add("v2");
        args.add("--as");
        args.add(resolveDocIdentity());
        args.add("--doc");
        args.add(docIdOrUrl.trim());
        if (hasText(docFormat)) {
            args.add("--doc-format");
            args.add(docFormat.trim());
        }
        if (hasText(detail)) {
            args.add("--detail");
            args.add(detail.trim());
        }
        if (hasText(scope)) {
            args.add("--scope");
            args.add(scope.trim());
        }
        if (hasText(startBlockId)) {
            args.add("--start-block-id");
            args.add(startBlockId.trim());
        }
        if (hasText(endBlockId)) {
            args.add("--end-block-id");
            args.add(endBlockId.trim());
        }
        if (hasText(keyword)) {
            args.add("--keyword");
            args.add(keyword);
        }

        JsonNode root = executeJson(args);
        JsonNode data = root.path("data");
        JsonNode document = data.path("document").isMissingNode() ? data : data.path("document");
        return LarkDocFetchResult.builder()
                .docId(firstNonBlank(text(document, "document_id"), text(data, "doc_id"), text(data, "document_id")))
                .docUrl(firstNonBlank(text(document, "url"), text(data, "doc_url"), text(data, "url"), docIdOrUrl))
                .revisionId(document.path("revision_id").asLong(data.path("revision_id").asLong(-1L)))
                .content(text(document, "content"))
                .docFormat(docFormat)
                .detail(detail)
                .scope(scope)
                .build();
    }

    private LarkDocUpdateResult parseUpdateResult(JsonNode root, String command) {
        JsonNode data = root.path("data");
        JsonNode document = data.path("document").isMissingNode() ? data : data.path("document");
        List<String> boardTokens = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<LarkDocBlockRef> newBlocks = new ArrayList<>();
        JsonNode boardTokensNode = data.path("board_tokens");
        if (boardTokensNode.isArray()) {
            boardTokensNode.forEach(node -> boardTokens.add(node.asText()));
        }
        JsonNode warningsNode = data.path("warnings");
        if (warningsNode.isArray()) {
            warningsNode.forEach(node -> warnings.add(node.asText()));
        }
        JsonNode newBlocksNode = document.path("new_blocks");
        if (newBlocksNode.isArray()) {
            newBlocksNode.forEach(node -> newBlocks.add(LarkDocBlockRef.builder()
                    .blockId(text(node, "block_id"))
                    .blockType(text(node, "block_type"))
                    .blockToken(text(node, "block_token"))
                    .build()));
        }
        return LarkDocUpdateResult.builder()
                .success(root.path("success").asBoolean(data.path("success").asBoolean(true)))
                .docId(firstNonBlank(text(document, "document_id"), text(data, "doc_id"), text(data, "document_id")))
                .mode(firstNonBlank(text(data, "mode"), command))
                .message(firstNonBlank(text(data, "message"), text(root, "message"), text(data, "result")))
                .revisionId(document.path("revision_id").asLong(data.path("revision_id").asLong(-1L)))
                .updatedBlocksCount(data.path("updated_blocks_count").asInt(0))
                .boardTokens(boardTokens)
                .warnings(warnings)
                .newBlocks(newBlocks)
                .build();
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

    private String defaultIfBlank(String value, String defaultValue) {
        return hasText(value) ? value.trim() : defaultValue;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
