package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import com.lark.imcollab.skills.lark.config.LarkDocProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LarkDocTool {

    private static final Pattern DOC_URL_PATTERN = Pattern.compile("/(?:docx|wiki)/([A-Za-z0-9]+)");
    private static final String CONTENT_STDIN_MARKER = "-";

    private final LarkCliClient larkCliClient;
    private final LarkCliProperties properties;
    private final LarkDocOpenApiClient openApiClient;
    private final LarkDocProperties docProperties;
    private final ObjectMapper objectMapper;
    private final Map<String, Set<String>> supportedFlagCache = new ConcurrentHashMap<>();

    private static final Pattern FLAG_PATTERN = Pattern.compile("(?<!\\S)--[a-zA-Z0-9-]+");
    private static final Set<String> FETCH_FALLBACK_FLAGS = Set.of(
            "--as", "--doc", "--format", "--limit", "--offset", "--profile"
    );
    private static final long DEFAULT_UPDATE_TIMEOUT_MILLIS = 180_000L;
    private static final long RICH_MEDIA_UPDATE_TIMEOUT_MILLIS = 300_000L;


    public LarkDocTool(
            LarkCliClient larkCliClient,
            LarkCliProperties properties,
            LarkDocOpenApiClient openApiClient,
            LarkDocProperties docProperties,
            ObjectMapper objectMapper
    ) {
        this.larkCliClient = larkCliClient;
        this.properties = properties;
        this.openApiClient = openApiClient;
        this.docProperties = docProperties;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Scenario C: create a Lark doc from markdown.")
    public LarkDocCreateResult createDoc(String title, String markdown) {
        requireValue(title, "title");
        requireValue(markdown, "markdown");

        if (openApiClient == null) {
            throw new IllegalStateException("飞书文档 OpenAPI 客户端未配置，无法创建文档。");
        }
        String normalizedTitle = normalizeTitle(title);
        String normalizedMarkdown = normalizeMarkdown(title, markdown);
        JsonNode document = createEmptyDocument(normalizedTitle);
        String documentId = firstNonBlank(text(document, "document_id"), text(document, "doc_id"));
        requireValue(documentId, "documentId");
        writeMarkdownBlocks(documentId, normalizedMarkdown);
        String docUrl = firstNonBlank(text(document, "url"), queryDocUrl(documentId), buildDocUrl(documentId));
        return LarkDocCreateResult.builder()
                .docId(documentId)
                .docUrl(docUrl)
                .message(firstNonBlank(
                        text(document, "message"),
                        "created by docx OpenAPI"
                ))
                .build();
    }

    private JsonNode createEmptyDocument(String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (docProperties != null && hasText(docProperties.getFolderToken())) {
            body.put("folder_token", docProperties.getFolderToken().trim());
        }
        body.put("title", title);
        JsonNode data = openApiClient.post(
                "/open-apis/docx/v1/documents",
                body,
                docRequestTimeoutSeconds()
        );
        JsonNode document = data.path("document");
        return document.isMissingNode() || document.isNull() ? data : document;
    }

    private String queryDocUrl(String documentId) {
        try {
            Map<String, Object> requestDoc = new LinkedHashMap<>();
            requestDoc.put("doc_token", documentId);
            requestDoc.put("doc_type", "docx");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("request_docs", List.of(requestDoc));
            body.put("with_url", true);
            JsonNode data = openApiClient.post(
                    "/open-apis/drive/v1/metas/batch_query",
                    body,
                    docRequestTimeoutSeconds()
            );
            JsonNode metas = data.path("metas");
            if (metas.isArray() && !metas.isEmpty()) {
                return text(metas.get(0), "url");
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to query Lark doc URL from Drive meta, fallback to configured domain: docId={}",
                    documentId, exception);
        }
        return "";
    }

    private void writeMarkdownBlocks(String documentId, String markdown) {
        ConvertedMarkdownBlocks converted = convertMarkdownToBlocks(documentId, markdown);
        List<Map<String, Object>> blocks = sanitizeConvertedBlocks(converted.blocks());
        if (blocks.isEmpty()) {
            return;
        }
        int batchSize = Math.min(1000, Math.max(1, docProperties == null ? 40 : docProperties.getMaxBlocksPerRequest()));
        List<ConvertedMarkdownBlockBatch> batches = splitConvertedBlocksIntoBatches(converted.firstLevelBlockIds(), blocks, batchSize);
        for (ConvertedMarkdownBlockBatch batch : batches) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("children_id", batch.childrenIds());
            body.put("descendants", batch.descendants());
            body.put("index", -1);
            openApiClient.post(
                    "/open-apis/docx/v1/documents/" + documentId + "/blocks/" + documentId
                            + "/descendant?client_token=" + UUID.randomUUID(),
                    body,
                    docRequestTimeoutSeconds()
            );
            pauseBetweenDocWrites();
        }
    }

    private List<ConvertedMarkdownBlockBatch> splitConvertedBlocksIntoBatches(
            List<String> preferredRootIds,
            List<Map<String, Object>> blocks,
            int batchSize
    ) {
        Map<String, Map<String, Object>> blocksById = new LinkedHashMap<>();
        for (Map<String, Object> block : blocks) {
            String blockId = stringValue(block.get("block_id"));
            if (hasText(blockId)) {
                blocksById.put(blockId, block);
            }
        }
        List<String> rootIds = firstLevelBlockIds(preferredRootIds, blocks);
        if (rootIds.isEmpty()) {
            return List.of(new ConvertedMarkdownBlockBatch(List.of(), blocks));
        }

        List<ConvertedMarkdownBlockBatch> batches = new ArrayList<>();
        List<String> currentRootIds = new ArrayList<>();
        LinkedHashSet<String> currentBlockIds = new LinkedHashSet<>();
        for (String rootId : rootIds) {
            LinkedHashSet<String> subtreeIds = collectSubtreeIds(rootId, blocksById);
            if (subtreeIds.isEmpty()) {
                continue;
            }
            if (!currentBlockIds.isEmpty() && currentBlockIds.size() + subtreeIds.size() > batchSize) {
                batches.add(toBlockBatch(currentRootIds, currentBlockIds, blocksById));
                currentRootIds = new ArrayList<>();
                currentBlockIds = new LinkedHashSet<>();
            }
            currentRootIds.add(rootId);
            currentBlockIds.addAll(subtreeIds);
        }
        if (!currentBlockIds.isEmpty()) {
            batches.add(toBlockBatch(currentRootIds, currentBlockIds, blocksById));
        }
        return batches;
    }

    private LinkedHashSet<String> collectSubtreeIds(String rootId, Map<String, Map<String, Object>> blocksById) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        collectSubtreeIds(rootId, blocksById, ids);
        return ids;
    }

    private void collectSubtreeIds(String blockId, Map<String, Map<String, Object>> blocksById, Set<String> ids) {
        if (!hasText(blockId) || ids.contains(blockId)) {
            return;
        }
        Map<String, Object> block = blocksById.get(blockId);
        if (block == null) {
            return;
        }
        ids.add(blockId);
        for (String childId : childIds(block)) {
            collectSubtreeIds(childId, blocksById, ids);
        }
    }

    private ConvertedMarkdownBlockBatch toBlockBatch(
            List<String> rootIds,
            LinkedHashSet<String> blockIds,
            Map<String, Map<String, Object>> blocksById
    ) {
        List<Map<String, Object>> descendants = blockIds.stream()
                .map(blocksById::get)
                .filter(block -> block != null)
                .toList();
        return new ConvertedMarkdownBlockBatch(List.copyOf(rootIds), descendants);
    }

    private ConvertedMarkdownBlocks convertMarkdownToBlocks(String documentId, String markdown) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content_type", "markdown");
        body.put("content", markdown);
        JsonNode data = openApiClient.post(
                "/open-apis/docx/v1/documents/blocks/convert",
                body,
                docRequestTimeoutSeconds()
        );
        JsonNode blocksNode = firstExisting(data, "blocks", "descendants");
        JsonNode firstLevelNode = firstExisting(data, "first_level_block_ids", "children_id", "children_ids");
        List<Map<String, Object>> blocks = blocksNode != null && blocksNode.isArray()
                ? objectMapper.convertValue(blocksNode, new TypeReference<>() {})
                : List.of();
        List<String> firstLevelBlockIds = firstLevelNode != null && firstLevelNode.isArray()
                ? objectMapper.convertValue(firstLevelNode, new TypeReference<>() {})
                : List.of();
        return new ConvertedMarkdownBlocks(blocks, firstLevelBlockIds);
    }

    private JsonNode firstExisting(JsonNode node, String... names) {
        if (node == null || names == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private List<Map<String, Object>> sanitizeConvertedBlocks(List<Map<String, Object>> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> sanitized = new ArrayList<>(blocks.size());
        for (Map<String, Object> block : blocks) {
            Map<String, Object> copy = deepCopyMap(block);
            removeReadOnlyMergeInfo(copy);
            sanitized.add(copy);
        }
        return sanitized;
    }

    private Map<String, Object> deepCopyMap(Map<String, Object> source) {
        if (source == null) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(source, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private void removeReadOnlyMergeInfo(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            map.remove("merge_info");
            for (Object child : new ArrayList<>(map.values())) {
                removeReadOnlyMergeInfo(child);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object child : list) {
                removeReadOnlyMergeInfo(child);
            }
        }
    }

    private List<String> firstLevelBlockIds(List<String> preferredIds, List<Map<String, Object>> descendants) {
        if (preferredIds != null && !preferredIds.isEmpty()) {
            Set<String> descendantIds = descendants == null
                    ? Set.of()
                    : descendants.stream()
                    .map(block -> stringValue(block.get("block_id")))
                    .filter(this::hasText)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            return preferredIds.stream()
                    .filter(this::hasText)
                    .filter(id -> descendantIds.isEmpty() || descendantIds.contains(id))
                    .toList();
        }
        if (descendants == null || descendants.isEmpty()) {
            return List.of();
        }
        Set<String> childIds = descendants.stream()
                .flatMap(block -> childIds(block).stream())
                .collect(java.util.stream.Collectors.toSet());
        return descendants.stream()
                .map(block -> stringValue(block.get("block_id")))
                .filter(this::hasText)
                .filter(id -> !childIds.contains(id))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> childIds(Map<String, Object> block) {
        Object children = block == null ? null : block.get("children");
        if (!(children instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(this::stringValue).filter(this::hasText).toList();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstMeaningfulLine(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        return content.lines()
                .map(String::trim)
                .filter(this::hasText)
                .filter(line -> !line.startsWith("<"))
                .findFirst()
                .orElseGet(() -> {
                    String normalized = content.replaceAll("\\s+", " ").trim();
                    return normalized.isBlank() ? null : normalized;
                });
    }

    private String toV1Markdown(String content, String docFormat) {
        if (content == null) {
            return null;
        }
        String normalizedFormat = docFormat == null ? "" : docFormat.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedFormat) {
            case "", "markdown" -> content;
            case "whiteboard" -> "<whiteboard token=\"" + content.trim() + "\"/>";
            case "image" -> "![image](" + content.trim() + ")";
            case "table" -> tableSpecToMarkdown(content);
            default -> content;
        };
    }

    private String tableSpecToMarkdown(String spec) {
        if (spec == null || spec.isBlank()) {
            return "| Col1 |\n| --- |\n| Val1 |";
        }
        String[] parts = spec.toLowerCase(Locale.ROOT).split("x");
        int cols = safePositive(parts, 0, 2);
        int rows = safePositive(parts, 1, 2);
        List<String> header = new ArrayList<>();
        List<String> separator = new ArrayList<>();
        for (int index = 0; index < cols; index++) {
            header.add("Col" + (index + 1));
            separator.add("---");
        }
        StringBuilder markdown = new StringBuilder();
        markdown.append("| ").append(String.join(" | ", header)).append(" |\n");
        markdown.append("| ").append(String.join(" | ", separator)).append(" |\n");
        for (int row = 1; row < rows; row++) {
            List<String> cells = new ArrayList<>();
            for (int col = 0; col < cols; col++) {
                cells.add(" ");
            }
            markdown.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
        return markdown.toString().trim();
    }

    private int safePositive(String[] parts, int index, int fallback) {
        if (parts == null || index >= parts.length) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(parts[index].trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int docRequestTimeoutSeconds() {
        return docProperties == null || docProperties.getRequestTimeoutSeconds() <= 0
                ? 30
                : docProperties.getRequestTimeoutSeconds();
    }

    private void pauseBetweenDocWrites() {
        try {
            Thread.sleep(380L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("飞书文档写入被中断。", exception);
        }
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
                .docId(firstNonBlank(
                        text(document, "document_id"),
                        text(data, "doc_id"),
                        text(data, "document_id")
                ))
                .docUrl(firstNonBlank(
                        text(document, "url"),
                        text(data, "doc_url"),
                        text(data, "url"),
                        docRef.trim()
                ))
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
        Set<String> supportedFlags = supportedFlags("+update");
        List<DocCommandAttempt> attempts = updateDocAttempts(
                docIdOrUrl.trim(),
                mode.trim(),
                markdown,
                "markdown",
                null,
                null,
                null,
                supportedFlags
        );
        return parseUpdateResult(executeJsonWithCompat("+update", attempts), mode);
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
        String normalizedCommand = command.trim();
        String docRef = docIdOrUrl.trim();
        long timeoutMillis = isRichMediaCommand(normalizedCommand)
                ? richMediaCommandTimeoutMillis()
                : commandTimeoutMillis();
        return switch (normalizedCommand) {
            case "append" -> updateDoc(docRef, "append", content);
            case "str_replace" -> updateBySelection(docRef, "replace_all", content, docFormat, null, pattern);
            case "block_insert_after" -> updateByBlockAnchor(docRef, "insert_after", content, docFormat, blockId);
            case "block_replace" -> updateByBlockAnchor(docRef, "replace_range", content, docFormat, blockId);
            case "block_delete" -> directBlockDelete(docRef, blockId);
            case "create_whiteboard" -> appendWhiteboard(docRef);
            default -> legacyUpdateByCommand(docRef, normalizedCommand, content, docFormat, blockId, pattern, revisionId, timeoutMillis);
        };
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
        return executeJson(args, null);
    }

    private JsonNode executeJson(List<String> args, String stdin) {
        CliCommandResult result = executeContentCommand(args, stdin, commandTimeoutMillis());
        if (!result.isSuccess()) {
            throw new IllegalStateException(readableCliError(args, result.output()));
        }
        try {
            return larkCliClient.readJsonOutput(result.output());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse lark doc response", exception);
        }
    }

    private CliCommandResult executeContentCommand(List<String> args, String content, long timeoutMillis) {
        if (content == null) {
            return larkCliClient.execute(args, null, timeoutMillis);
        }
        Path contentFile = null;
        try {
            Path baseDirectory = cliWorkingDirectory();
            Path tempDirectory = baseDirectory.resolve(".lark-doc-content");
            Files.createDirectories(tempDirectory);
            contentFile = Files.createTempFile(tempDirectory, "content-", ".md");
            Files.writeString(contentFile, content, StandardCharsets.UTF_8);
            return larkCliClient.execute(withContentFileArg(args, baseDirectory.relativize(contentFile)), null, timeoutMillis);
        } catch (Exception exception) {
            throw new IllegalStateException("飞书文档内容暂存失败，请稍后重试。", exception);
        } finally {
            if (contentFile != null) {
                try {
                    Files.deleteIfExists(contentFile);
                } catch (Exception exception) {
                    log.warn("Failed to delete temporary Lark doc content file: {}", contentFile, exception);
                }
            }
        }
    }

    private Path cliWorkingDirectory() {
        String configured = properties.getWorkingDirectory();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private List<String> withContentFileArg(List<String> args, Path contentFile) {
        List<String> rewritten = new ArrayList<>(args);
        String fileArg = "@" + contentFile;
        for (int index = 1; index < rewritten.size(); index++) {
            String previous = rewritten.get(index - 1);
            if (("--content".equals(previous) || "--markdown".equals(previous))
                    && CONTENT_STDIN_MARKER.equals(rewritten.get(index))) {
                rewritten.set(index, fileArg);
                return rewritten;
            }
        }
        return rewritten;
    }

    private JsonNode executeJsonWithCompat(String docsCommand, List<DocCommandAttempt> attempts) {
        return executeJsonWithCompat(docsCommand, attempts, commandTimeoutMillis());
    }

    private JsonNode executeJsonWithCompat(String docsCommand, List<DocCommandAttempt> attempts, long timeoutMillis) {
        IllegalStateException lastError = null;
        for (int index = 0; index < attempts.size(); index++) {
            DocCommandAttempt attempt = attempts.get(index);
            List<String> args = attempt.args();
            CliCommandResult result = executeContentCommand(args, attempt.stdin(), timeoutMillis);
            if (result.isSuccess()) {
                try {
                    return larkCliClient.readJsonOutput(result.output());
                } catch (Exception exception) {
                    throw new IllegalStateException("Failed to parse lark doc response", exception);
                }
            }
            String output = result.output();
            String message = readableCliError(args, output);
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
        return Set.of("--as", "--profile");
    }

    private List<DocCommandAttempt> fetchDocAttempts(String docRef, String scope, String detail, Set<String> supportedFlags) {
        Map<String, DocCommandAttempt> attempts = new LinkedHashMap<>();
        attempts.put("preferred", new DocCommandAttempt(fetchDocArgs(docRef, scope, detail, supportedFlags, true, true), null));
        attempts.put("no-api-version", new DocCommandAttempt(fetchDocArgs(docRef, scope, detail, supportedFlags, false, true), null));
        attempts.put("basic", new DocCommandAttempt(fetchDocArgs(docRef, null, null, Set.of("--as", "--doc"), false, false), null));
        return distinctAttempts(attempts);
    }

    private List<DocCommandAttempt> updateDocAttempts(
            String docRef,
            String command,
            String content,
            String docFormat,
            String blockId,
            String pattern,
            Long revisionId,
            Set<String> supportedFlags
    ) {
        Map<String, DocCommandAttempt> attempts = new LinkedHashMap<>();
        attempts.put("preferred", updateDocAttempt(
                docRef,
                command,
                content,
                docFormat,
                blockId,
                pattern,
                revisionId,
                supportedFlags,
                true,
                true
        ));
        attempts.put("no-api-version", updateDocAttempt(
                docRef,
                command,
                content,
                docFormat,
                blockId,
                pattern,
                revisionId,
                supportedFlags,
                false,
                true
        ));
        attempts.put("basic", updateDocAttempt(
                docRef,
                command,
                content,
                docFormat,
                blockId,
                pattern,
                revisionId,
                Set.of("--as", "--doc", "--command", "--content", "--doc-format", "--block-id", "--pattern", "--revision-id"),
                false,
                false
        ));
        return distinctAttempts(attempts);
    }

    private LarkDocUpdateResult updateBySelection(
            String docRef,
            String mode,
            String markdown,
            String docFormat,
            String titleSelection,
            String ellipsisSelection
    ) {
        Set<String> supportedFlags = supportedFlags("+update");
        List<DocCommandAttempt> attempts = v1UpdateAttempts(
                docRef,
                mode,
                markdown,
                titleSelection,
                ellipsisSelection,
                null,
                supportedFlags
        );
        return parseUpdateResult(executeJsonWithCompat("+update", attempts), mode);
    }

    private LarkDocUpdateResult updateByBlockAnchor(
            String docRef,
            String mode,
            String content,
            String docFormat,
            String blockId
    ) {
        requireValue(blockId, "blockId");
        LarkDocFetchResult anchorFetch = fetchDocSectionMarkdown(docRef, blockId.trim());
        String anchorSelection = firstMeaningfulLine(anchorFetch.getContent());
        if (!hasText(anchorSelection)) {
            throw new IllegalStateException("无法从目标 block 提取可定位文本，无法执行文档更新。");
        }
        String markdown = toV1Markdown(content, docFormat);
        return updateBySelection(docRef, mode, markdown, "markdown", null, anchorSelection);
    }

    private LarkDocUpdateResult directBlockDelete(String docRef, String blockId) {
        requireValue(blockId, "blockId");
        Set<String> supportedFlags = supportedFlags("+update");
        List<DocCommandAttempt> attempts = updateDocAttempts(
                docRef, "block_delete", null, null, blockId.trim(), null, null, supportedFlags
        );
        return parseUpdateResult(executeJsonWithCompat("+update", attempts), "block_delete");
    }

    private LarkDocUpdateResult deleteByBlockAnchor(String docRef, String blockId) {
        requireValue(blockId, "blockId");
        LarkDocFetchResult anchorFetch = fetchDocSectionMarkdown(docRef, blockId.trim());
        String anchorSelection = firstMeaningfulLine(anchorFetch.getContent());
        if (!hasText(anchorSelection)) {
            throw new IllegalStateException("无法从目标 block 提取可定位文本，无法执行删除。");
        }
        return updateBySelection(docRef, "delete_range", null, null, null, anchorSelection);
    }

    private LarkDocUpdateResult appendWhiteboard(String docRef) {
        LarkDocUpdateResult result = updateDoc(docRef, "append", "<whiteboard type=\"blank\"></whiteboard>");
        if (result.getBoardTokens() != null && !result.getBoardTokens().isEmpty()
                && (result.getNewBlocks() == null || result.getNewBlocks().isEmpty())) {
            List<LarkDocBlockRef> newBlocks = result.getBoardTokens().stream()
                    .filter(this::hasText)
                    .map(token -> LarkDocBlockRef.builder().blockId(token).blockToken(token).blockType("whiteboard").build())
                    .toList();
            result.setNewBlocks(newBlocks);
        }
        return result;
    }

    private LarkDocUpdateResult legacyUpdateByCommand(
            String docRef,
            String command,
            String content,
            String docFormat,
            String blockId,
            String pattern,
            Long revisionId,
            long timeoutMillis
    ) {
        Set<String> supportedFlags = supportedFlags("+update");
        List<DocCommandAttempt> attempts = updateDocAttempts(
                docRef,
                command,
                content,
                docFormat,
                blockId,
                pattern,
                revisionId,
                supportedFlags
        );
        return parseUpdateResult(executeJsonWithCompat("+update", attempts, timeoutMillis), command);
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

    private DocCommandAttempt updateDocAttempt(
            String docRef,
            String command,
            String content,
            String docFormat,
            String blockId,
            String pattern,
            Long revisionId,
            Set<String> supportedFlags,
            boolean withApiVersion,
            boolean includeIdentity
    ) {
        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+update");
        if (withApiVersion) {
            appendIfSupported(args, supportedFlags, "--api-version", "v2");
        }
        if (includeIdentity) {
            appendIfSupported(args, supportedFlags, "--as", resolveDocIdentity());
        }
        appendIfSupported(args, supportedFlags, "--doc", docRef);
        appendIfSupported(args, supportedFlags, "--command", command);
        if (hasText(docFormat)) {
            appendIfSupported(args, supportedFlags, "--doc-format", docFormat.trim());
        }
        String stdin = null;
        if (content != null) {
            if (supportedFlags.contains("--content")) {
                args.add("--content");
                args.add(CONTENT_STDIN_MARKER);
                stdin = content;
            }
        }
        if (hasText(blockId)) {
            appendIfSupported(args, supportedFlags, "--block-id", blockId.trim());
        }
        if (hasText(pattern)) {
            appendIfSupported(args, supportedFlags, "--pattern", pattern);
        }
        if (revisionId != null && revisionId >= 0) {
            appendIfSupported(args, supportedFlags, "--revision-id", String.valueOf(revisionId));
        }
        return new DocCommandAttempt(args, stdin);
    }

    private List<DocCommandAttempt> v1UpdateAttempts(
            String docRef,
            String mode,
            String markdown,
            String titleSelection,
            String ellipsisSelection,
            String newTitle,
            Set<String> supportedFlags
    ) {
        Map<String, DocCommandAttempt> attempts = new LinkedHashMap<>();
        attempts.put("preferred", v1UpdateAttempt(docRef, mode, markdown, titleSelection, ellipsisSelection, newTitle, supportedFlags, true, true));
        attempts.put("no-api-version", v1UpdateAttempt(docRef, mode, markdown, titleSelection, ellipsisSelection, newTitle, supportedFlags, false, true));
        attempts.put("basic", v1UpdateAttempt(
                docRef,
                mode,
                markdown,
                titleSelection,
                ellipsisSelection,
                newTitle,
                Set.of("--as", "--doc", "--mode", "--markdown", "--selection-by-title", "--selection-with-ellipsis", "--new-title"),
                false,
                false
        ));
        return distinctAttempts(attempts);
    }

    private DocCommandAttempt v1UpdateAttempt(
            String docRef,
            String mode,
            String markdown,
            String titleSelection,
            String ellipsisSelection,
            String newTitle,
            Set<String> supportedFlags,
            boolean withApiVersion,
            boolean includeIdentity
    ) {
        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+update");
        if (withApiVersion) {
            appendIfSupported(args, supportedFlags, "--api-version", "v1");
        }
        if (includeIdentity) {
            appendIfSupported(args, supportedFlags, "--as", resolveDocIdentity());
        }
        appendIfSupported(args, supportedFlags, "--doc", docRef);
        appendIfSupported(args, supportedFlags, "--mode", mode);
        if (hasText(markdown)) {
            appendIfSupported(args, supportedFlags, "--markdown", CONTENT_STDIN_MARKER);
        }
        appendIfSupported(args, supportedFlags, "--selection-by-title", titleSelection);
        appendIfSupported(args, supportedFlags, "--selection-with-ellipsis", ellipsisSelection);
        appendIfSupported(args, supportedFlags, "--new-title", newTitle);
        return new DocCommandAttempt(args, hasText(markdown) ? markdown : null);
    }

    private List<DocCommandAttempt> distinctAttempts(Map<String, DocCommandAttempt> attempts) {
        List<DocCommandAttempt> result = new ArrayList<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        for (DocCommandAttempt attempt : attempts.values()) {
            if (attempt == null || attempt.args() == null || attempt.args().isEmpty()) {
                continue;
            }
            String fingerprint = String.join("\u0000", attempt.args()) + "\u0001" + (attempt.stdin() == null ? "" : "stdin");
            if (fingerprints.add(fingerprint)) {
                result.add(attempt);
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

    private String readableCliError(List<String> args, String output) {
        String extracted = larkCliClient.extractErrorMessage(output);
        if (extracted == null || extracted.isBlank()) {
            return "飞书文档工具调用失败，请稍后重试。";
        }
        if (output != null && output.contains("lark-cli command timed out")) {
            return "lark-cli command timed out while running " + String.join(" ", args);
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
                || lowerMessage.contains("command line is too long")
                || lowerMessage.contains("commandline is too long")
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
        if (timeoutSeconds <= 0) {
            return DEFAULT_UPDATE_TIMEOUT_MILLIS;
        }
        return timeoutSeconds * 1000L;
    }

    private long richMediaCommandTimeoutMillis() {
        long configured = commandTimeoutMillis();
        return Math.max(configured, RICH_MEDIA_UPDATE_TIMEOUT_MILLIS);
    }

    private boolean isRichMediaCommand(String command) {
        if (command == null) {
            return false;
        }
        return switch (command) {
            case "upload_image", "create_whiteboard", "table_write", "block_insert_after" -> true;
            default -> false;
        };
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        return field.isMissingNode() || field.isNull() ? "" : field.asText("");
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
        List<DocCommandAttempt> attempts = fetchDocFullAttempts(
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

    private List<DocCommandAttempt> fetchDocFullAttempts(
            String docRef,
            String scope,
            String docFormat,
            String detail,
            String startBlockId,
            String endBlockId,
            String keyword,
            Set<String> supportedFlags
    ) {
        Map<String, DocCommandAttempt> attempts = new LinkedHashMap<>();
        attempts.put("preferred", fetchDocFullAttempt(docRef, scope, docFormat, detail, startBlockId, endBlockId, keyword, supportedFlags, true, true));
        attempts.put("no-api-version", fetchDocFullAttempt(docRef, scope, docFormat, detail, startBlockId, endBlockId, keyword, supportedFlags, false, true));
        attempts.put("basic", fetchDocFullAttempt(docRef, null, null, null, null, null, null, Set.of("--as", "--doc"), false, false));
        return distinctAttempts(attempts);
    }

    private DocCommandAttempt fetchDocFullAttempt(
            String docRef,
            String scope,
            String docFormat,
            String detail,
            String startBlockId,
            String endBlockId,
            String keyword,
            Set<String> supportedFlags,
            boolean withApiVersion,
            boolean includeIdentity
    ) {
        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+fetch");
        if (withApiVersion) {
            appendIfSupported(args, supportedFlags, "--api-version", "v2");
        }
        if (includeIdentity) {
            appendIfSupported(args, supportedFlags, "--as", resolveDocIdentity());
        }
        appendIfSupported(args, supportedFlags, "--doc", docRef);
        if (hasText(docFormat)) {
            appendIfSupported(args, supportedFlags, "--doc-format", docFormat.trim());
        }
        if (hasText(detail)) {
            appendIfSupported(args, supportedFlags, "--detail", detail.trim());
        }
        if (hasText(scope)) {
            appendIfSupported(args, supportedFlags, "--scope", scope.trim());
        }
        if (hasText(startBlockId)) {
            appendIfSupported(args, supportedFlags, "--start-block-id", startBlockId.trim());
        }
        if (hasText(endBlockId)) {
            appendIfSupported(args, supportedFlags, "--end-block-id", endBlockId.trim());
        }
        if (hasText(keyword)) {
            appendIfSupported(args, supportedFlags, "--keyword", keyword);
        }
        return new DocCommandAttempt(args, null);
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

    private String normalizeTitle(String title) {
        String normalized = title.trim();
        return normalized.length() <= 800 ? normalized : normalized.substring(0, 800);
    }

    private String buildDocUrl(String documentId) {
        String baseUrl = docProperties == null ? "" : docProperties.getWebBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://feishu.cn/docx/" + documentId;
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/docx")) {
            return normalized + "/" + documentId;
        }
        if (normalized.endsWith("/docx/")) {
            return normalized + documentId;
        }
        if (normalized.endsWith("/")) {
            return normalized + "docx/" + documentId;
        }
        return normalized + "/docx/" + documentId;
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

    private record DocCommandAttempt(List<String> args, String stdin) {
    }

    private record ConvertedMarkdownBlocks(List<Map<String, Object>> blocks, List<String> firstLevelBlockIds) {
    }

    private record ConvertedMarkdownBlockBatch(List<String> childrenIds, List<Map<String, Object>> descendants) {
    }
}
