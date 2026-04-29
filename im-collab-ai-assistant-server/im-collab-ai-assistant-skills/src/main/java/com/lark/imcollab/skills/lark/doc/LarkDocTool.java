package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.databind.JsonNode;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LarkDocTool {

    private final LarkCliClient larkCliClient;

    public LarkDocTool(LarkCliClient larkCliClient) {
        this.larkCliClient = larkCliClient;
    }

    @Tool(description = "Scenario C: create a Lark doc from markdown.")
    public LarkDocCreateResult createDoc(String title, String markdown) {
        requireValue(title, "title");
        requireValue(markdown, "markdown");

        List<String> args = List.of(
                "docs", "+create",
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
        if (!"delete_range".equals(mode)) {
            requireValue(markdown, "markdown");
        }

        List<String> args = new ArrayList<>();
        args.add("docs");
        args.add("+update");
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
}
