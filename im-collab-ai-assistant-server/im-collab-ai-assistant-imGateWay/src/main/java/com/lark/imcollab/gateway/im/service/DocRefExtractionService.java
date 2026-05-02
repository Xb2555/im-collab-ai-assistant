package com.lark.imcollab.gateway.im.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocRefExtractionService {

    private static final Pattern DOC_REF_PATTERN = Pattern.compile(
            "https://[\\w.-]+\\.(?:feishu\\.cn|larksuite\\.com)/(?:docx|docs|wiki)/[A-Za-z0-9]+(?:\\?[^\\s\"'<>]*)?",
            Pattern.CASE_INSENSITIVE
    );

    private final ObjectMapper objectMapper;

    public DocRefExtractionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> extractDocRefs(String content, String rawContent) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        collectMatches(content, refs);
        collectMatches(rawContent, refs);
        collectFromJson(rawContent, refs);
        return List.copyOf(refs);
    }

    private void collectFromJson(String rawContent, Set<String> refs) {
        if (rawContent == null || rawContent.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(rawContent);
            visitNode(root, refs);
        } catch (Exception ignored) {
            // Raw content may already be plain text; direct regex scan already covered it.
        }
    }

    private void visitNode(JsonNode node, Set<String> refs) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isTextual()) {
            collectMatches(node.asText(), refs);
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                visitNode(child, refs);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> visitNode(entry.getValue(), refs));
        }
    }

    private void collectMatches(String text, Set<String> refs) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = DOC_REF_PATTERN.matcher(text);
        while (matcher.find()) {
            String ref = normalize(matcher.group());
            if (!ref.isBlank()) {
                refs.add(ref);
            }
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        while (!normalized.isEmpty() && isTrailingPunctuation(normalized.charAt(normalized.length() - 1))) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isTrailingPunctuation(char ch) {
        return switch (ch) {
            case '。', '，', ',', ';', '；', '）', ')', '】', ']', '>', '》', '"', '\'' -> true;
            default -> false;
        };
    }
}
