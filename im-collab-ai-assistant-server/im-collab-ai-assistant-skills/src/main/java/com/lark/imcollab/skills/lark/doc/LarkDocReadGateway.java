package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.databind.JsonNode;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class LarkDocReadGateway {

    private static final String CONTENT_STDIN_MARKER = "-";
    private static final Set<String> FETCH_FALLBACK_FLAGS = Set.of(
            "--as", "--doc", "--format", "--limit", "--offset", "--profile"
    );
    private static final Pattern FLAG_PATTERN = Pattern.compile("(?<!\\S)--[a-zA-Z0-9_-]+");

    private final LarkCliClient larkCliClient;
    private final LarkCliProperties properties;
    private final Map<String, Set<String>> supportedFlagCache = new ConcurrentHashMap<>();

    public LarkDocReadGateway(LarkCliClient larkCliClient, LarkCliProperties properties) {
        this.larkCliClient = larkCliClient;
        this.properties = properties;
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

    public LarkDocFetchResult fetchDoc(
            String docIdOrUrl,
            String scope,
            String docFormat,
            String detail,
            String startBlockId,
            String endBlockId,
            String keyword
    ) {
        requireValue(docIdOrUrl, "docIdOrUrl");
        Set<String> supportedFlags = supportedFlags("+fetch");
        if (!supportedFlags.contains("--doc")) {
            throw new IllegalStateException("当前 lark-cli docs +fetch 不支持 --doc 参数，请升级 lark-cli 后重试。");
        }
        List<DocCommandAttempt> attempts = fetchDocAttempts(
                docIdOrUrl.trim(), scope, docFormat, detail, startBlockId, endBlockId, keyword, supportedFlags
        );
        JsonNode root = executeJsonWithCompat("+fetch", attempts);
        JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
        JsonNode document = data.path("document").isMissingNode() ? data : data.path("document");
        return LarkDocFetchResult.builder()
                .docId(firstNonBlank(text(document, "document_id"), text(data, "doc_id"), text(data, "document_id")))
                .docUrl(firstNonBlank(text(document, "url"), text(data, "doc_url"), text(data, "url"), docIdOrUrl))
                .revisionId(document.path("revision_id").asLong(data.path("revision_id").asLong(-1L)))
                .content(firstNonBlank(
                        text(data, "content"), text(document, "content"),
                        text(data, "markdown"), text(document, "markdown"),
                        text(data, "xml"), text(document, "xml"),
                        text(data, "text"), text(document, "text"),
                        text(data, "raw_content"), text(document, "raw_content")
                ))
                .docFormat(docFormat)
                .detail(detail)
                .scope(scope)
                .build();
    }

    // ---- internals ----

    private List<DocCommandAttempt> fetchDocAttempts(
            String docRef, String scope, String docFormat, String detail,
            String startBlockId, String endBlockId, String keyword, Set<String> supportedFlags
    ) {
        Map<String, DocCommandAttempt> attempts = new LinkedHashMap<>();
        attempts.put("preferred", fetchDocAttempt(docRef, scope, docFormat, detail, startBlockId, endBlockId, keyword, supportedFlags, true, true));
        attempts.put("no-api-version", fetchDocAttempt(docRef, scope, docFormat, detail, startBlockId, endBlockId, keyword, supportedFlags, false, true));
        attempts.put("basic", fetchDocAttempt(docRef, null, null, null, null, null, null, Set.of("--as", "--doc"), false, false));
        return distinctAttempts(attempts);
    }

    private DocCommandAttempt fetchDocAttempt(
            String docRef, String scope, String docFormat, String detail,
            String startBlockId, String endBlockId, String keyword,
            Set<String> supportedFlags, boolean withApiVersion, boolean includeIdentity
    ) {
        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+fetch");
        if (withApiVersion) appendIfSupported(args, supportedFlags, "--api-version", "v2");
        if (includeIdentity) appendIfSupported(args, supportedFlags, "--as", resolveDocIdentity());
        appendIfSupported(args, supportedFlags, "--doc", docRef);
        if (hasText(docFormat)) appendIfSupported(args, supportedFlags, "--doc-format", docFormat.trim());
        if (hasText(detail)) appendIfSupported(args, supportedFlags, "--detail", detail.trim());
        if (hasText(scope)) appendIfSupported(args, supportedFlags, "--scope", scope.trim());
        if (hasText(startBlockId)) appendIfSupported(args, supportedFlags, "--start-block-id", startBlockId.trim());
        if (hasText(endBlockId)) appendIfSupported(args, supportedFlags, "--end-block-id", endBlockId.trim());
        if (hasText(keyword)) appendIfSupported(args, supportedFlags, "--keyword", keyword);
        return new DocCommandAttempt(args, null);
    }

    JsonNode executeJsonWithCompat(String docsCommand, List<DocCommandAttempt> attempts) {
        return executeJsonWithCompat(docsCommand, attempts, commandTimeoutMillis());
    }

    JsonNode executeJsonWithCompat(String docsCommand, List<DocCommandAttempt> attempts, long timeoutMillis) {
        IllegalStateException lastError = null;
        for (int index = 0; index < attempts.size(); index++) {
            DocCommandAttempt attempt = attempts.get(index);
            log.info("LARK_DOC_CLI attempt command={} index={} args={} stdinLength={} timeoutMs={}",
                    docsCommand,
                    index + 1,
                    attempt.args(),
                    attempt.stdin() == null ? 0 : attempt.stdin().length(),
                    timeoutMillis);
            CliCommandResult result = executeContentCommand(attempt.args(), attempt.stdin(), timeoutMillis);
            if (result.isSuccess()) {
                try {
                    log.info("LARK_DOC_CLI success command={} index={} outputPreview={}",
                            docsCommand, index + 1, previewOutput(result.output()));
                    return larkCliClient.readJsonOutput(result.output());
                } catch (Exception e) {
                    log.error("LARK_DOC_CLI parse_failed command={} index={} outputPreview={}",
                            docsCommand, index + 1, previewOutput(result.output()), e);
                    throw new IllegalStateException("Failed to parse lark doc response", e);
                }
            }
            String output = result.output();
            log.warn("LARK_DOC_CLI failed command={} index={} rawOutput={}",
                    docsCommand, index + 1, previewOutput(output));
            String message = readableCliError(attempt.args(), output);
            lastError = new IllegalStateException(message);
            if (!isUnknownFlagError(output) || index == attempts.size() - 1) throw lastError;
            supportedFlagCache.remove(docsCommand);
            log.warn("Lark docs command hit flag incompatibility, retrying: command={}, attempt={}, error={}", docsCommand, index + 1, message);
        }
        throw lastError == null ? new IllegalStateException("飞书文档工具调用失败，请稍后重试。") : lastError;
    }

    CliCommandResult executeContentCommand(List<String> args, String content, long timeoutMillis) {
        if (content == null) return larkCliClient.execute(args, null, timeoutMillis);
        if (usesDirectStdin(args)) return larkCliClient.execute(args, content, timeoutMillis);
        Path contentFile = null;
        try {
            Path baseDir = cliWorkingDirectory();
            Path tempDir = baseDir.resolve(".lark-doc-content");
            Files.createDirectories(tempDir);
            contentFile = Files.createTempFile(tempDir, "content-", ".md");
            Files.writeString(contentFile, content, StandardCharsets.UTF_8);
            List<String> rewrittenArgs = withContentFileArg(args, baseDir.relativize(contentFile));
            log.info("LARK_DOC_CLI content_file mode args={} tempFile={} contentLength={}",
                    rewrittenArgs, contentFile.toAbsolutePath(), content.length());
            return larkCliClient.execute(rewrittenArgs, null, timeoutMillis);
        } catch (Exception e) {
            throw new IllegalStateException("飞书文档内容暂存失败，请稍后重试。", e);
        } finally {
            if (contentFile != null) {
                try { Files.deleteIfExists(contentFile); } catch (Exception ignored) {}
            }
        }
    }

    private boolean usesDirectStdin(List<String> args) {
        for (int i = 1; i < args.size(); i++) {
            String previous = args.get(i - 1);
            if ("--content".equals(previous)
                    && CONTENT_STDIN_MARKER.equals(args.get(i))) {
                return true;
            }
        }
        return false;
    }

    private List<String> withContentFileArg(List<String> args, Path contentFile) {
        List<String> rewritten = new ArrayList<>(args);
        String fileArg = "@" + contentFile;
        for (int i = 1; i < rewritten.size(); i++) {
            String prev = rewritten.get(i - 1);
            if (("--content".equals(prev) || "--source".equals(prev) || "--markdown".equals(prev))
                    && CONTENT_STDIN_MARKER.equals(rewritten.get(i))) {
                rewritten.set(i, fileArg);
                return rewritten;
            }
        }
        return rewritten;
    }

    Set<String> supportedFlags(String docsCommand) {
        return supportedFlagCache.computeIfAbsent(docsCommand, cmd -> {
            CliCommandResult baseHelp = larkCliClient.execute(List.of("docs", cmd, "--help"));
            if (!baseHelp.isSuccess()) return fallbackFlags(cmd);
            Set<String> flags = parseFlags(baseHelp.output());
            if (flags.contains("--api-version")) {
                CliCommandResult versionedHelp = larkCliClient.execute(List.of("docs", cmd, "--api-version", "v2", "--help"));
                if (versionedHelp.isSuccess()) {
                    Set<String> vf = parseFlags(versionedHelp.output());
                    if (!vf.isEmpty()) return vf;
                }
            }
            return flags.isEmpty() ? fallbackFlags(cmd) : flags;
        });
    }

    private Set<String> parseFlags(String helpOutput) {
        if (helpOutput == null || helpOutput.isBlank()) return Set.of();
        Set<String> flags = new LinkedHashSet<>();
        Matcher m = FLAG_PATTERN.matcher(helpOutput);
        while (m.find()) flags.add(m.group());
        return flags;
    }

    private Set<String> fallbackFlags(String docsCommand) {
        return "+fetch".equals(docsCommand) ? FETCH_FALLBACK_FLAGS : Set.of("--as", "--profile");
    }

    List<DocCommandAttempt> distinctAttempts(Map<String, DocCommandAttempt> attempts) {
        List<DocCommandAttempt> result = new ArrayList<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        for (DocCommandAttempt attempt : attempts.values()) {
            if (attempt == null || attempt.args() == null || attempt.args().isEmpty()) continue;
            String fp = String.join(" ", attempt.args()) + "" + (attempt.stdin() == null ? "" : "stdin");
            if (fingerprints.add(fp)) result.add(attempt);
        }
        return result;
    }

    void appendIfSupported(List<String> args, Set<String> supportedFlags, String flag, String value) {
        if (!supportedFlags.contains(flag) || value == null || value.isBlank()) return;
        args.add(flag);
        args.add(value);
    }

    private String readableCliError(List<String> args, String output) {
        String extracted = larkCliClient.extractErrorMessage(output);
        if (extracted == null || extracted.isBlank()) return "飞书文档工具调用失败，请稍后重试。";
        if (output != null && output.contains("lark-cli command timed out"))
            return "lark-cli command timed out while running " + String.join(" ", args);
        String lower = extracted.toLowerCase(Locale.ROOT);
        if (lower.contains("unknown flag")) {
            Matcher m = FLAG_PATTERN.matcher(extracted);
            String flag = m.find() ? m.group() : "";
            return flag.isBlank() ? "飞书文档工具参数不兼容，请检查 lark-cli 版本后重试。"
                    : "飞书文档工具参数不兼容：当前 lark-cli 不支持 " + flag + "。";
        }
        if (lower.contains("powershell") || lower.contains(".ps1") || lower.contains("java.lang.")
                || lower.contains("exception:") || lower.contains("processbuilder"))
            return "飞书文档创建失败，请检查 lark-cli 可执行配置、登录状态或文档权限后重试。";
        String firstLine = extracted.lines().map(String::trim).filter(l -> !l.isBlank()).findFirst()
                .orElse("飞书文档工具调用失败，请稍后重试。");
        return firstLine.length() <= 220 ? firstLine : firstLine.substring(0, 220) + "...";
    }

    private boolean isUnknownFlagError(String output) {
        String extracted = larkCliClient.extractErrorMessage(output);
        return extracted != null && extracted.toLowerCase(Locale.ROOT).contains("unknown flag");
    }

    long commandTimeoutMillis() {
        int t = properties.getTimeoutSeconds();
        return t <= 0 ? 180_000L : t * 1000L;
    }

    String resolveDocIdentity() {
        String id = properties.getDocIdentity();
        return id == null || id.isBlank() ? "user" : id.trim();
    }

    String text(JsonNode node, String field) {
        JsonNode f = node.path(field);
        return f.isMissingNode() || f.isNull() ? "" : f.asText("");
    }

    String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(fieldName + " must be provided");
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return hasText(value) ? value.trim() : defaultValue;
    }

    private String previewOutput(String output) {
        if (output == null) {
            return "";
        }
        String normalized = output.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }

    private Path cliWorkingDirectory() {
        String configured = properties.getWorkingDirectory();
        if (configured != null && !configured.isBlank()) return Path.of(configured.trim()).toAbsolutePath().normalize();
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    record DocCommandAttempt(List<String> args, String stdin) {}
}
