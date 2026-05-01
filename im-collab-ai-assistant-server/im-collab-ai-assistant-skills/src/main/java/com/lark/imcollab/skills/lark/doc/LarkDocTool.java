package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.databind.JsonNode;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LarkDocTool {

    private static final Pattern DOC_URL_PATTERN = Pattern.compile("/(?:docx|wiki)/([A-Za-z0-9]+)");

    private final LarkCliClient larkCliClient;
    private final LarkCliProperties properties;

    public LarkDocTool(LarkCliClient larkCliClient, LarkCliProperties properties) {
        this.larkCliClient = larkCliClient;
        this.properties = properties;
    }

    @Tool(description = "Scenario C: create a Lark doc from markdown.")
    public LarkDocCreateResult createDoc(String title, String markdown) {
        requireValue(title, "title");
        requireValue(markdown, "markdown");

        List<String> args = List.of(
                "docs", "+create",
                "--as", resolveDocIdentity(),
                "--api-version", "v2",
                "--doc-format", "markdown",
                "--content", normalizeMarkdown(title, markdown)
        );
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
        CliCommandResult result = larkCliClient.execute(args);
        if (!result.isSuccess()) {
            throw new IllegalStateException(larkCliClient.extractErrorMessage(result.output()));
        }
        try {
            return larkCliClient.readJsonOutput(result.output());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse lark doc response", exception);
        }
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
