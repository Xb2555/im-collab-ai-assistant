package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.databind.JsonNode;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LarkDocTool {

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

    @Tool(description = "Scenario B/C: fetch readable content from a Lark doc by URL or token.")
    public LarkDocFetchResult fetchDoc(String docRef, String scope, String detail) {
        requireValue(docRef, "docRef");
        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+fetch");
        args.add("--as");
        args.add(resolveDocIdentity());
        args.add("--api-version");
        args.add("v2");
        args.add("--doc");
        args.add(docRef.trim());
        if (scope != null && !scope.isBlank()) {
            args.add("--scope");
            args.add(scope.trim());
        }
        if (detail != null && !detail.isBlank()) {
            args.add("--detail");
            args.add(detail.trim());
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
