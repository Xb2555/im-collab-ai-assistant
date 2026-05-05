package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.databind.JsonNode;
import com.lark.imcollab.skills.lark.doc.LarkDocReadGateway.DocCommandAttempt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class LarkDocWriteGateway {

    private static final String CONTENT_STDIN_MARKER = "-";
    private static final long DEFAULT_UPDATE_TIMEOUT_MILLIS = 180_000L;
    private static final long RICH_MEDIA_UPDATE_TIMEOUT_MILLIS = 300_000L;

    private final LarkDocReadGateway readGateway;

    public LarkDocWriteGateway(LarkDocReadGateway readGateway) {
        this.readGateway = readGateway;
    }

    public LarkDocUpdateResult updateDoc(String docIdOrUrl, String command, String markdown) {
        requireValue(docIdOrUrl, "docIdOrUrl");
        requireValue(command, "command");
        if (!"delete_range".equals(command) && !"block_delete".equals(command)) requireValue(markdown, "markdown");
        Set<String> supportedFlags = readGateway.supportedFlags("+update");
        List<DocCommandAttempt> attempts = updateDocAttempts(docIdOrUrl.trim(), command.trim(), markdown, "markdown", null, null, null, supportedFlags);
        return parseUpdateResult(readGateway.executeJsonWithCompat("+update", attempts), command);
    }

    public LarkDocUpdateResult updateByCommand(String docIdOrUrl, String command, String content, String docFormat, String blockId, String pattern, Long revisionId) {
        requireValue(docIdOrUrl, "docIdOrUrl");
        requireValue(command, "command");
        String normalizedCommand = command.trim();
        String docRef = docIdOrUrl.trim();
        return switch (normalizedCommand) {
            case "append" -> updateDoc(docRef, "append", content);
            case "str_replace" -> executeCommandUpdate(docRef, normalizedCommand, content, docFormat, null, pattern, revisionId, readGateway.commandTimeoutMillis());
            case "block_insert_after", "block_replace" -> executeCommandUpdate(docRef, normalizedCommand, content, docFormat, blockId, null, revisionId, richMediaTimeoutMillis());
            case "block_delete" -> directBlockDelete(docRef, blockId);
            case "create_whiteboard" -> appendWhiteboard(docRef);
            default -> executeCommandUpdate(docRef, normalizedCommand, content, docFormat, blockId, pattern, revisionId,
                    readGateway.commandTimeoutMillis());
        };
    }

    public LarkDocUpdateResult appendMarkdown(String docIdOrUrl, String markdown) {
        return updateDoc(docIdOrUrl, "append", markdown);
    }

    public LarkDocUpdateResult overwriteMarkdown(String docIdOrUrl, String markdown) {
        requireValue(docIdOrUrl, "docIdOrUrl");
        requireValue(markdown, "markdown");
        List<DocCommandAttempt> attempts = overwriteMarkdownAttempts(docIdOrUrl.trim(), markdown);
        log.info("LARK_DOC_WRITE overwrite_markdown docRef={} attemptCount={} markdownLength={}",
                docIdOrUrl.trim(), attempts.size(), markdown.length());
        return parseUpdateResult(readGateway.executeJsonWithCompat("+update", attempts, richMediaTimeoutMillis()), "overwrite");
    }

    public LarkDocUpdateResult updateWhiteboard(String whiteboardToken, String source, String inputFormat) {
        requireValue(whiteboardToken, "whiteboardToken");
        requireValue(source, "source");
        String normalizedFormat = inputFormat == null || inputFormat.isBlank() ? "raw" : inputFormat.trim().toLowerCase();
        Set<String> supportedFlags = readGateway.supportedFlags("+whiteboard-update");
        List<DocCommandAttempt> attempts = whiteboardUpdateAttempts(whiteboardToken.trim(), source, normalizedFormat, supportedFlags);
        log.info("LARK_DOC_WRITE update_whiteboard token={} inputFormat={} attemptCount={} sourceLength={}",
                whiteboardToken.trim(), normalizedFormat, attempts.size(), source.length());
        return parseUpdateResult(readGateway.executeJsonWithCompat("+whiteboard-update", attempts, richMediaTimeoutMillis()), "whiteboard_update");
    }

    // ---- internals ----

    private LarkDocUpdateResult directBlockDelete(String docRef, String blockId) {
        requireValue(blockId, "blockId");
        Set<String> supportedFlags = readGateway.supportedFlags("+update");
        List<DocCommandAttempt> attempts = updateDocAttempts(docRef, "block_delete", null, null, blockId.trim(), null, null, supportedFlags);
        return parseUpdateResult(readGateway.executeJsonWithCompat("+update", attempts), "block_delete");
    }

    private LarkDocUpdateResult appendWhiteboard(String docRef) {
        LarkDocUpdateResult result = updateDoc(docRef, "append", "<whiteboard type=\"blank\"></whiteboard>");
        if (result.getBoardTokens() != null && !result.getBoardTokens().isEmpty()
                && (result.getNewBlocks() == null || result.getNewBlocks().isEmpty())) {
            List<LarkDocBlockRef> newBlocks = result.getBoardTokens().stream()
                    .filter(readGateway::hasText)
                    .map(token -> LarkDocBlockRef.builder().blockId(token).blockToken(token).blockType("whiteboard").build())
                    .toList();
            result.setNewBlocks(newBlocks);
        }
        return result;
    }

    private LarkDocUpdateResult executeCommandUpdate(
            String docRef,
            String command,
            String content,
            String docFormat,
            String blockId,
            String pattern,
            Long revisionId,
            long timeoutMillis
    ) {
        Set<String> supportedFlags = readGateway.supportedFlags("+update");
        List<DocCommandAttempt> attempts = updateDocAttempts(docRef, command, content, normalizeDocFormat(docFormat), blockId, pattern, revisionId, supportedFlags);
        return parseUpdateResult(readGateway.executeJsonWithCompat("+update", attempts, timeoutMillis), command);
    }

    private List<DocCommandAttempt> overwriteMarkdownAttempts(String docRef, String markdown) {
        Map<String, DocCommandAttempt> attempts = new LinkedHashMap<>();
        attempts.put("legacy-v1", new DocCommandAttempt(List.of(
                "docs", "+update",
                "--as", readGateway.resolveDocIdentity(),
                "--doc", docRef,
                "--mode", "overwrite",
                "--markdown", CONTENT_STDIN_MARKER
        ), markdown));
        attempts.put("legacy-v1-no-as", new DocCommandAttempt(List.of(
                "docs", "+update",
                "--doc", docRef,
                "--mode", "overwrite",
                "--markdown", CONTENT_STDIN_MARKER
        ), markdown));
        return readGateway.distinctAttempts(attempts);
    }

    private List<DocCommandAttempt> whiteboardUpdateAttempts(String whiteboardToken, String source, String inputFormat, Set<String> supportedFlags) {
        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+whiteboard-update");
        readGateway.appendIfSupported(args, supportedFlags, "--as", readGateway.resolveDocIdentity());
        readGateway.appendIfSupported(args, supportedFlags, "--whiteboard-token", whiteboardToken);
        readGateway.appendIfSupported(args, supportedFlags, "--input_format", inputFormat);
        readGateway.appendIfSupported(args, supportedFlags, "--source", CONTENT_STDIN_MARKER);
        if (supportedFlags.contains("--overwrite")) {
            args.add("--overwrite");
        }
        if (supportedFlags.contains("--yes")) {
            args.add("--yes");
        }
        return List.of(new DocCommandAttempt(args, source));
    }

    private List<DocCommandAttempt> updateDocAttempts(String docRef, String command, String content, String docFormat, String blockId, String pattern, Long revisionId, Set<String> supportedFlags) {
        Map<String, DocCommandAttempt> attempts = new LinkedHashMap<>();
        attempts.put("preferred", updateDocAttempt(docRef, command, content, docFormat, blockId, pattern, revisionId, supportedFlags, true, true));
        attempts.put("no-api-version", updateDocAttempt(docRef, command, content, docFormat, blockId, pattern, revisionId, supportedFlags, false, true));
        attempts.put("basic", updateDocAttempt(docRef, command, content, docFormat, blockId, pattern, revisionId,
                Set.of("--as", "--doc", "--command", "--content", "--doc-format", "--block-id", "--pattern", "--revision-id", "--src-block-ids"), false, false));
        return readGateway.distinctAttempts(attempts);
    }

    private DocCommandAttempt updateDocAttempt(String docRef, String command, String content, String docFormat, String blockId, String pattern, Long revisionId, Set<String> supportedFlags, boolean withApiVersion, boolean includeIdentity) {
        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+update");
        if (withApiVersion) readGateway.appendIfSupported(args, supportedFlags, "--api-version", "v2");
        if (includeIdentity) readGateway.appendIfSupported(args, supportedFlags, "--as", readGateway.resolveDocIdentity());
        readGateway.appendIfSupported(args, supportedFlags, "--doc", docRef);
        readGateway.appendIfSupported(args, supportedFlags, "--command", command);
        if (readGateway.hasText(docFormat)) readGateway.appendIfSupported(args, supportedFlags, "--doc-format", docFormat.trim());
        String stdin = null;
        if (content != null && supportedFlags.contains("--content")) {
            args.add("--content");
            args.add(CONTENT_STDIN_MARKER);
            stdin = content;
        }
        if ("block_move_after".equals(command)) {
            if (readGateway.hasText(pattern)) {
                readGateway.appendIfSupported(args, supportedFlags, "--block-id", pattern.trim());
            }
            if (readGateway.hasText(blockId)) {
                if (supportedFlags.contains("--src-block-ids")) {
                    args.add("--src-block-ids");
                    args.add(blockId.trim());
                } else {
                    readGateway.appendIfSupported(args, supportedFlags, "--pattern", pattern);
                }
            }
        } else {
            if (readGateway.hasText(blockId)) readGateway.appendIfSupported(args, supportedFlags, "--block-id", blockId.trim());
            if (readGateway.hasText(pattern)) readGateway.appendIfSupported(args, supportedFlags, "--pattern", pattern);
        }
        if (revisionId != null && revisionId >= 0) readGateway.appendIfSupported(args, supportedFlags, "--revision-id", String.valueOf(revisionId));
        return new DocCommandAttempt(args, stdin);
    }

    private LarkDocUpdateResult parseUpdateResult(JsonNode root, String command) {
        JsonNode data = root.path("data");
        JsonNode document = data.path("document").isMissingNode() ? data : data.path("document");
        List<String> boardTokens = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<LarkDocBlockRef> newBlocks = new ArrayList<>();
        JsonNode boardTokensNode = data.path("board_tokens");
        if (boardTokensNode.isArray()) boardTokensNode.forEach(n -> boardTokens.add(n.asText()));
        JsonNode warningsNode = data.path("warnings");
        if (warningsNode.isArray()) warningsNode.forEach(n -> warnings.add(n.asText()));
        JsonNode newBlocksNode = document.path("new_blocks");
        if (newBlocksNode.isArray()) newBlocksNode.forEach(n -> newBlocks.add(LarkDocBlockRef.builder()
                .blockId(readGateway.text(n, "block_id"))
                .blockType(readGateway.text(n, "block_type"))
                .blockToken(readGateway.text(n, "block_token"))
                .build()));
        return LarkDocUpdateResult.builder()
                .success(root.path("success").asBoolean(data.path("success").asBoolean(true)))
                .docId(readGateway.firstNonBlank(readGateway.text(document, "document_id"), readGateway.text(data, "doc_id"), readGateway.text(data, "document_id")))
                .mode(readGateway.firstNonBlank(readGateway.text(data, "mode"), command))
                .message(readGateway.firstNonBlank(readGateway.text(data, "message"), readGateway.text(root, "message"), readGateway.text(data, "result")))
                .revisionId(document.path("revision_id").asLong(data.path("revision_id").asLong(-1L)))
                .updatedBlocksCount(data.path("updated_blocks_count").asInt(0))
                .boardTokens(boardTokens)
                .warnings(warnings)
                .newBlocks(newBlocks)
                .build();
    }

    private String normalizeDocFormat(String docFormat) {
        if (docFormat == null) {
            return "";
        }
        return switch (docFormat.trim().toLowerCase()) {
            case "image", "img" -> "markdown";
            default -> docFormat.trim().toLowerCase();
        };
    }

    private String firstMeaningfulLine(String content) {
        if (content == null || content.isBlank()) return null;
        return content.lines().map(String::trim).filter(readGateway::hasText)
                .filter(l -> !l.startsWith("<")).findFirst()
                .orElseGet(() -> { String n = content.replaceAll("\\s+", " ").trim(); return n.isBlank() ? null : n; });
    }

    private long richMediaTimeoutMillis() {
        return Math.max(readGateway.commandTimeoutMillis(), RICH_MEDIA_UPDATE_TIMEOUT_MILLIS);
    }

    private void requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(fieldName + " must be provided");
    }
}
