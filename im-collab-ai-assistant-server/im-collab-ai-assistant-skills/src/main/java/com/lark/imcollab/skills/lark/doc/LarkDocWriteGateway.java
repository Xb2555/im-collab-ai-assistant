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

    public LarkDocUpdateResult updateDoc(String docIdOrUrl, String mode, String markdown) {
        requireValue(docIdOrUrl, "docIdOrUrl");
        requireValue(mode, "mode");
        if (!"delete_range".equals(mode) && !"block_delete".equals(mode)) requireValue(markdown, "markdown");
        Set<String> supportedFlags = readGateway.supportedFlags("+update");
        // append uses v1 protocol (--mode/--markdown), other modes use v2 (--command)
        List<DocCommandAttempt> attempts = "append".equals(mode)
                ? v1UpdateAttempts(docIdOrUrl.trim(), mode, markdown, null, null, null, supportedFlags)
                : updateDocAttempts(docIdOrUrl.trim(), mode.trim(), markdown, "markdown", null, null, null, supportedFlags);
        return parseUpdateResult(readGateway.executeJsonWithCompat("+update", attempts), mode);
    }

    public LarkDocUpdateResult updateByCommand(String docIdOrUrl, String command, String content, String docFormat, String blockId, String pattern, Long revisionId) {
        requireValue(docIdOrUrl, "docIdOrUrl");
        requireValue(command, "command");
        String normalizedCommand = command.trim();
        String docRef = docIdOrUrl.trim();
        long timeoutMillis = isRichMediaCommand(normalizedCommand) ? richMediaTimeoutMillis() : readGateway.commandTimeoutMillis();
        return switch (normalizedCommand) {
            case "append" -> updateDoc(docRef, "append", content);
            case "str_replace" -> legacyUpdate(docRef, "str_replace", content, docFormat, null, pattern, revisionId, timeoutMillis);
            case "block_insert_after" -> legacyUpdate(docRef, "block_insert_after", content, docFormat, blockId, null, revisionId, timeoutMillis);
            case "block_replace" -> legacyUpdate(docRef, "block_replace", content, docFormat, blockId, null, revisionId, timeoutMillis);
            case "block_delete" -> directBlockDelete(docRef, blockId);
            case "create_whiteboard" -> appendWhiteboard(docRef);
            default -> legacyUpdate(docRef, normalizedCommand, content, docFormat, blockId, pattern, revisionId, timeoutMillis);
        };
    }

    public LarkDocUpdateResult appendMarkdown(String docIdOrUrl, String markdown) {
        return updateDoc(docIdOrUrl, "append", markdown);
    }

    // ---- internals ----

    private LarkDocUpdateResult updateBySelection(String docRef, String mode, String markdown, String docFormat, String titleSelection, String ellipsisSelection) {
        Set<String> supportedFlags = readGateway.supportedFlags("+update");
        List<DocCommandAttempt> attempts = v1UpdateAttempts(docRef, mode, markdown, titleSelection, ellipsisSelection, null, supportedFlags);
        return parseUpdateResult(readGateway.executeJsonWithCompat("+update", attempts), mode);
    }

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

    private LarkDocUpdateResult legacyUpdate(String docRef, String command, String content, String docFormat, String blockId, String pattern, Long revisionId, long timeoutMillis) {
        Set<String> supportedFlags = readGateway.supportedFlags("+update");
        List<DocCommandAttempt> attempts = updateDocAttempts(docRef, command, content, docFormat, blockId, pattern, revisionId, supportedFlags);
        return parseUpdateResult(readGateway.executeJsonWithCompat("+update", attempts, timeoutMillis), command);
    }

    private List<DocCommandAttempt> updateDocAttempts(String docRef, String command, String content, String docFormat, String blockId, String pattern, Long revisionId, Set<String> supportedFlags) {
        Map<String, DocCommandAttempt> attempts = new LinkedHashMap<>();
        attempts.put("preferred", updateDocAttempt(docRef, command, content, docFormat, blockId, pattern, revisionId, supportedFlags, true, true));
        attempts.put("no-api-version", updateDocAttempt(docRef, command, content, docFormat, blockId, pattern, revisionId, supportedFlags, false, true));
        attempts.put("basic", updateDocAttempt(docRef, command, content, docFormat, blockId, pattern, revisionId,
                Set.of("--as", "--doc", "--command", "--content", "--doc-format", "--block-id", "--pattern", "--revision-id"), false, false));
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
        if (readGateway.hasText(blockId)) readGateway.appendIfSupported(args, supportedFlags, "--block-id", blockId.trim());
        if (readGateway.hasText(pattern)) readGateway.appendIfSupported(args, supportedFlags, "--pattern", pattern);
        if (revisionId != null && revisionId >= 0) readGateway.appendIfSupported(args, supportedFlags, "--revision-id", String.valueOf(revisionId));
        return new DocCommandAttempt(args, stdin);
    }

    private List<DocCommandAttempt> v1UpdateAttempts(String docRef, String mode, String markdown, String titleSelection, String ellipsisSelection, String newTitle, Set<String> supportedFlags) {
        Map<String, DocCommandAttempt> attempts = new LinkedHashMap<>();
        attempts.put("preferred", v1UpdateAttempt(docRef, mode, markdown, titleSelection, ellipsisSelection, newTitle, supportedFlags, true, true));
        attempts.put("no-api-version", v1UpdateAttempt(docRef, mode, markdown, titleSelection, ellipsisSelection, newTitle, supportedFlags, false, true));
        attempts.put("basic", v1UpdateAttempt(docRef, mode, markdown, titleSelection, ellipsisSelection, newTitle,
                Set.of("--as", "--doc", "--mode", "--markdown", "--selection-by-title", "--selection-with-ellipsis", "--new-title"), false, false));
        return readGateway.distinctAttempts(attempts);
    }

    private DocCommandAttempt v1UpdateAttempt(String docRef, String mode, String markdown, String titleSelection, String ellipsisSelection, String newTitle, Set<String> supportedFlags, boolean withApiVersion, boolean includeIdentity) {
        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+update");
        if (withApiVersion) readGateway.appendIfSupported(args, supportedFlags, "--api-version", "v1");
        if (includeIdentity) readGateway.appendIfSupported(args, supportedFlags, "--as", readGateway.resolveDocIdentity());
        readGateway.appendIfSupported(args, supportedFlags, "--doc", docRef);
        readGateway.appendIfSupported(args, supportedFlags, "--mode", mode);
        if (readGateway.hasText(markdown)) readGateway.appendIfSupported(args, supportedFlags, "--markdown", CONTENT_STDIN_MARKER);
        readGateway.appendIfSupported(args, supportedFlags, "--selection-by-title", titleSelection);
        readGateway.appendIfSupported(args, supportedFlags, "--selection-with-ellipsis", ellipsisSelection);
        readGateway.appendIfSupported(args, supportedFlags, "--new-title", newTitle);
        return new DocCommandAttempt(args, readGateway.hasText(markdown) ? markdown : null);
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

    private String toV1Markdown(String content, String docFormat) {
        if (content == null) return null;
        String fmt = docFormat == null ? "" : docFormat.trim().toLowerCase();
        return switch (fmt) {
            case "", "markdown" -> content;
            case "whiteboard" -> "<whiteboard token=\"" + content.trim() + "\"/>";
            case "image" -> "![image](" + content.trim() + ")";
            default -> content;
        };
    }

    private String firstMeaningfulLine(String content) {
        if (content == null || content.isBlank()) return null;
        return content.lines().map(String::trim).filter(readGateway::hasText)
                .filter(l -> !l.startsWith("<")).findFirst()
                .orElseGet(() -> { String n = content.replaceAll("\\s+", " ").trim(); return n.isBlank() ? null : n; });
    }

    private boolean isRichMediaCommand(String command) {
        if (command == null) return false;
        return switch (command) {
            case "upload_image", "create_whiteboard", "table_write", "block_insert_after" -> true;
            default -> false;
        };
    }

    private long richMediaTimeoutMillis() {
        return Math.max(readGateway.commandTimeoutMillis(), RICH_MEDIA_UPDATE_TIMEOUT_MILLIS);
    }

    private void requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(fieldName + " must be provided");
    }
}
