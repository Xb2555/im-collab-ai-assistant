package com.lark.imcollab.skills.lark.doc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import com.lark.imcollab.skills.lark.config.LarkDocProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class LarkDocTool {

    private static final Pattern DOC_URL_PATTERN = Pattern.compile("/(?:docx|wiki)/([A-Za-z0-9]+)");

    private final LarkDocReadGateway readGateway;
    private final LarkDocWriteGateway writeGateway;
    private final LarkDocContentCodec contentCodec;
    private final LarkDocOpenApiClient openApiClient;
    private final LarkDocProperties docProperties;

    // backward-compatible constructor for tests and legacy wiring
    public LarkDocTool(
            LarkCliClient larkCliClient,
            LarkCliProperties cliProperties,
            LarkDocOpenApiClient openApiClient,
            LarkDocProperties docProperties,
            ObjectMapper objectMapper
    ) {
        LarkDocReadGateway readGw = new LarkDocReadGateway(larkCliClient, cliProperties);
        this.readGateway = readGw;
        this.writeGateway = new LarkDocWriteGateway(readGw);
        this.contentCodec = new LarkDocContentCodec(openApiClient, docProperties, objectMapper);
        this.openApiClient = openApiClient;
        this.docProperties = docProperties;
    }

    @Autowired
    public LarkDocTool(
            LarkDocReadGateway readGateway,
            LarkDocWriteGateway writeGateway,
            LarkDocContentCodec contentCodec,
            LarkDocOpenApiClient openApiClient,
            LarkDocProperties docProperties
    ) {
        this.readGateway = readGateway;
        this.writeGateway = writeGateway;
        this.contentCodec = contentCodec;
        this.openApiClient = openApiClient;
        this.docProperties = docProperties;
    }

    @Tool(description = "Scenario C: create a Lark doc from markdown.")
    public LarkDocCreateResult createDoc(String title, String markdown) {
        requireValue(title, "title");
        requireValue(markdown, "markdown");
        if (openApiClient == null) throw new IllegalStateException("飞书文档 OpenAPI 客户端未配置，无法创建文档。");
        String normalizedTitle = normalizeTitle(title);
        String normalizedMarkdown = normalizeMarkdown(title, markdown);
        JsonNode document = createEmptyDocument(normalizedTitle);
        String documentId = readGateway.firstNonBlank(readGateway.text(document, "document_id"), readGateway.text(document, "doc_id"));
        requireValue(documentId, "documentId");
        contentCodec.writeMarkdownBlocks(documentId, normalizedMarkdown);
        String docUrl = readGateway.firstNonBlank(readGateway.text(document, "url"), queryDocUrl(documentId), buildDocUrl(documentId));
        return LarkDocCreateResult.builder()
                .docId(documentId)
                .docUrl(docUrl)
                .message(readGateway.firstNonBlank(readGateway.text(document, "message"), "created by docx OpenAPI"))
                .build();
    }

    @Tool(description = "Scenario C: append markdown content to an existing Lark doc.")
    public LarkDocUpdateResult appendMarkdown(String docIdOrUrl, String markdown) {
        return writeGateway.appendMarkdown(docIdOrUrl, markdown);
    }

    @Tool(description = "Scenario B/C: fetch readable content from a Lark doc by URL or token.")
    public LarkDocFetchResult fetchDoc(String docRef, String scope, String detail) {
        return readGateway.fetchDoc(docRef, scope, "xml", detail, null, null, null);
    }

    public LarkDocUpdateResult updateDocByCommand(String docIdOrUrl, String command, String markdown) {
        return writeGateway.updateDoc(docIdOrUrl, command, markdown);
    }

    public LarkDocUpdateResult updateDoc(String docIdOrUrl, String command, String markdown) {
        return updateDocByCommand(docIdOrUrl, command, markdown);
    }

    public LarkDocFetchResult fetchDocOutline(String docIdOrUrl) {
        return readGateway.fetchDocOutline(docIdOrUrl);
    }

    public LarkDocFetchResult fetchDocSection(String docIdOrUrl, String startBlockId, String detail) {
        return readGateway.fetchDocSection(docIdOrUrl, startBlockId, detail);
    }

    public LarkDocFetchResult fetchDocFull(String docIdOrUrl, String detail) {
        return readGateway.fetchDocFull(docIdOrUrl, detail);
    }

    public LarkDocFetchResult fetchDocByKeyword(String docIdOrUrl, String keyword, String detail) {
        return readGateway.fetchDocByKeyword(docIdOrUrl, keyword, detail);
    }

    public LarkDocFetchResult fetchDocSectionMarkdown(String docIdOrUrl, String startBlockId) {
        return readGateway.fetchDocSectionMarkdown(docIdOrUrl, startBlockId);
    }

    public LarkDocFetchResult fetchDocRangeMarkdown(String docIdOrUrl, String startBlockId, String endBlockId) {
        return readGateway.fetchDocRangeMarkdown(docIdOrUrl, startBlockId, endBlockId);
    }

    public LarkDocFetchResult fetchDocFullMarkdown(String docIdOrUrl) {
        return readGateway.fetchDocFullMarkdown(docIdOrUrl);
    }

    public LarkDocUpdateResult updateByCommand(String docIdOrUrl, String command, String content, String docFormat, String blockId, String pattern, Long revisionId) {
        return writeGateway.updateByCommand(docIdOrUrl, command, content, docFormat, blockId, pattern, revisionId);
    }

    public String extractDocumentId(String docIdOrUrl) {
        requireValue(docIdOrUrl, "docIdOrUrl");
        String trimmed = docIdOrUrl.trim();
        Matcher matcher = DOC_URL_PATTERN.matcher(trimmed);
        return matcher.find() ? matcher.group(1) : trimmed;
    }

    public LarkDocFetchResult fetchDoc(String docIdOrUrl, String scope, String docFormat, String detail, String startBlockId, String endBlockId, String keyword) {
        return readGateway.fetchDoc(docIdOrUrl, scope, docFormat, detail, startBlockId, endBlockId, keyword);
    }

    // ---- OpenAPI create helpers ----

    private JsonNode createEmptyDocument(String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (docProperties != null && readGateway.hasText(docProperties.getFolderToken())) {
            body.put("folder_token", docProperties.getFolderToken().trim());
        }
        body.put("title", title);
        JsonNode data = openApiClient.post("/open-apis/docx/v1/documents", body, timeoutSeconds());
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
            JsonNode data = openApiClient.post("/open-apis/drive/v1/metas/batch_query", body, timeoutSeconds());
            JsonNode metas = data.path("metas");
            if (metas.isArray() && !metas.isEmpty()) return readGateway.text(metas.get(0), "url");
        } catch (RuntimeException e) {
            log.warn("Failed to query Lark doc URL from Drive meta: docId={}", documentId, e);
        }
        return "";
    }

    private String buildDocUrl(String documentId) {
        String baseUrl = docProperties == null ? "" : docProperties.getWebBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) return "https://feishu.cn/docx/" + documentId;
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/docx")) return normalized + "/" + documentId;
        if (normalized.endsWith("/docx/")) return normalized + documentId;
        if (normalized.endsWith("/")) return normalized + "docx/" + documentId;
        return normalized + "/docx/" + documentId;
    }

    private String normalizeMarkdown(String title, String markdown) {
        String trimmed = markdown.trim();
        return trimmed.startsWith("# ") ? trimmed : "# " + title.trim() + "\n\n" + trimmed;
    }

    private String normalizeTitle(String title) {
        String normalized = title.trim();
        return normalized.length() <= 800 ? normalized : normalized.substring(0, 800);
    }

    private int timeoutSeconds() {
        return docProperties == null || docProperties.getRequestTimeoutSeconds() <= 0 ? 30 : docProperties.getRequestTimeoutSeconds();
    }

    private void requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(fieldName + " must be provided");
    }
}
