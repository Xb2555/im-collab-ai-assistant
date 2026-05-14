package com.lark.imcollab.skills.lark.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DefaultLarkMessageQueryExpansionService implements LarkMessageQueryExpansionService {
    private static final Logger log = LoggerFactory.getLogger(DefaultLarkMessageQueryExpansionService.class);
    private static final Set<String> BANNED_TERMS = Set.of("项目", "消息", "内容", "讨论", "文档");
    private static final Pattern QUARTER_PATTERN = Pattern.compile("\\bQ[1-4]\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIXED_TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z]+\\d+[A-Za-z0-9_-]*\\b");
    private static final Pattern CHINESE_TOKEN_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]{2,12}");

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public DefaultLarkMessageQueryExpansionService(
            ObjectProvider<ChatModel> chatModelProvider,
            ObjectMapper objectMapper
    ) {
        this.chatModel = chatModelProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> expandQueries(
            String userQuery,
            String originalQuery,
            String startTime,
            String endTime,
            int maxQueries
    ) {
        int safeMaxQueries = Math.max(1, Math.min(3, maxQueries));
        List<String> heuristicQueries = heuristicQueries(userQuery, originalQuery, safeMaxQueries);
        if (chatModel == null || !hasText(originalQuery)) {
            return heuristicQueries;
        }
        try {
            String response = chatModel.call(buildPrompt(userQuery, originalQuery, startTime, endTime, safeMaxQueries));
            List<String> llmQueries = parseQueries(response, originalQuery, safeMaxQueries);
            return llmQueries.isEmpty() ? heuristicQueries : llmQueries;
        } catch (Exception exception) {
            log.warn("Failed to expand IM search query, falling back to heuristics: {}", exception.getMessage());
            return heuristicQueries;
        }
    }

    private String buildPrompt(
            String userQuery,
            String originalQuery,
            String startTime,
            String endTime,
            int maxQueries
    ) {
        return """
                你是 IM 消息检索里的 query expansion 子组件。
                你的任务不是回答用户，只从原始表达里提取 1-%d 个最短、最独特、最可能直接出现在消息正文里的检索短 query。

                输出约束：
                - 只输出 JSON。
                - queries 数量必须在 1 到 %d 之间。
                - 禁止整句复述，禁止输出长句。
                - 禁止输出这些泛词：项目、消息、内容、讨论、文档。
                - 优先保留业务主题词、时间/阶段标签、产品/功能独特名词。
                - 可以输出类似 Q3、智能工作流、智能体 这种短 query。
                - 不要输出与 originalQuery 完全相同的字符串。

                用户原话：%s
                originalQuery：%s
                startTime：%s
                endTime：%s

                只输出 JSON：
                {"queries":["Q3","智能体"]}
                """.formatted(
                maxQueries,
                maxQueries,
                safe(userQuery),
                safe(originalQuery),
                safe(startTime),
                safe(endTime)
        );
    }

    private List<String> parseQueries(String response, String originalQuery, int maxQueries) throws Exception {
        String json = extractJson(response);
        JsonNode root = objectMapper.readTree(json);
        JsonNode queriesNode = root.path("queries");
        if (!queriesNode.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (JsonNode queryNode : queriesNode) {
            if (queries.size() >= maxQueries) {
                break;
            }
            String normalized = normalizeCandidate(queryNode.asText(), originalQuery);
            if (normalized != null) {
                queries.add(normalized);
            }
        }
        return List.copyOf(queries);
    }

    private List<String> heuristicQueries(String userQuery, String originalQuery, int maxQueries) {
        String source = firstNonBlank(userQuery, originalQuery);
        if (!hasText(source)) {
            return List.of();
        }
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        Matcher quarterMatcher = QUARTER_PATTERN.matcher(source);
        while (quarterMatcher.find() && queries.size() < maxQueries) {
            String normalized = normalizeCandidate(quarterMatcher.group(), originalQuery);
            if (normalized != null) {
                queries.add(normalized);
            }
        }
        Matcher mixedMatcher = MIXED_TOKEN_PATTERN.matcher(source);
        while (mixedMatcher.find() && queries.size() < maxQueries) {
            String normalized = normalizeCandidate(mixedMatcher.group(), originalQuery);
            if (normalized != null) {
                queries.add(normalized);
            }
        }
        for (String token : extractChineseCandidates(source)) {
            if (queries.size() >= maxQueries) {
                break;
            }
            String normalized = normalizeCandidate(token, originalQuery);
            if (normalized != null) {
                queries.add(normalized);
            }
        }
        return List.copyOf(queries);
    }

    private List<String> extractChineseCandidates(String source) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = CHINESE_TOKEN_PATTERN.matcher(source);
        while (matcher.find()) {
            String raw = matcher.group();
            String cleaned = raw
                    .replaceAll("^(有关|关于|根据|围绕|针对|相关|本次|这个|那个|这些|那些)+", "")
                    .replaceAll("(消息|记录|聊天|内容|讨论|文档|材料|总结|生成|整理)+$", "")
                    .replaceAll("(项目|方案|系统|平台|流程|能力|功能|模块)+$", "")
                    .trim();
            if (hasText(cleaned)) {
                tokens.add(cleaned);
            }
        }
        return tokens;
    }

    private String normalizeCandidate(String candidate, String originalQuery) {
        if (!hasText(candidate)) {
            return null;
        }
        String normalized = candidate.trim()
                .replaceAll("^[：:;；,，。.!！?？\\s]+", "")
                .replaceAll("[：:;；,，。.!！?？\\s]+$", "");
        if (!hasText(normalized)) {
            return null;
        }
        if (normalized.length() > 12) {
            return null;
        }
        if (BANNED_TERMS.contains(normalized)) {
            return null;
        }
        if (normalize(normalized).equals(normalize(originalQuery))) {
            return null;
        }
        if (normalized.contains(" ") || normalized.contains("\n")) {
            return null;
        }
        return normalized;
    }

    private String extractJson(String response) {
        String text = response == null ? "" : response.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
