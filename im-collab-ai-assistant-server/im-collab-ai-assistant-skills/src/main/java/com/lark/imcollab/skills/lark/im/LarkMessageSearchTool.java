package com.lark.imcollab.skills.lark.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class LarkMessageSearchTool {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_LIMIT = 5;

    private final LarkCliClient cliClient;
    private final LarkCliProperties properties;

    public LarkMessageSearchTool(LarkCliClient cliClient, LarkCliProperties properties) {
        this.cliClient = cliClient;
        this.properties = properties;
    }

    @Tool(description = "Scenario A: search Lark IM messages by keyword using lark-cli user identity.")
    public LarkMessageSearchResult searchMessages(
            String query,
            String chatId,
            String startTime,
            String endTime,
            Integer pageSize,
            Integer pageLimit
    ) {
        if (!hasText(query)) {
            return listChatMessages(chatId, startTime, endTime, pageSize, pageLimit);
        }
        return searchByKeyword(query, chatId, startTime, endTime, pageSize, pageLimit);
    }

    private LarkMessageSearchResult searchByKeyword(
            String query,
            String chatId,
            String startTime,
            String endTime,
            Integer pageSize,
            Integer pageLimit
    ) {
        List<String> args = new ArrayList<>();
        args.add("im");
        args.add("+messages-search");
        args.add("--as");
        args.add("user");
        if (hasText(query)) {
            args.add("--query");
            args.add(query.trim());
        }
        if (hasText(chatId)) {
            args.add("--chat-id");
            args.add(chatId.trim());
        }
        if (hasText(startTime)) {
            args.add("--start");
            args.add(startTime.trim());
        }
        if (hasText(endTime)) {
            args.add("--end");
            args.add(endTime.trim());
        }
        args.add("--page-size");
        args.add(String.valueOf(normalize(pageSize, DEFAULT_PAGE_SIZE, 1, 50)));
        args.add("--page-limit");
        args.add(String.valueOf(normalize(pageLimit, DEFAULT_PAGE_LIMIT, 1, 40)));
        args.add("--format");
        args.add("json");

        try {
            log.info("LARK_IM_SEARCH_CLI_START mode=messages-search chatId={} query='{}' start={} end={} pageSize={} pageLimit={}",
                    safe(chatId),
                    safe(query),
                    safe(startTime),
                    safe(endTime),
                    normalize(pageSize, DEFAULT_PAGE_SIZE, 1, 50),
                    normalize(pageLimit, DEFAULT_PAGE_LIMIT, 1, 40));
            JsonNode root = cliClient.executeJson(args, null, commandTimeoutMillis());
            LarkMessageSearchResult parsed = parse(root);
            log.info("LARK_IM_SEARCH_CLI_RESULT mode=messages-search chatId={} query='{}' itemCount={} hasMore={}",
                    safe(chatId),
                    safe(query),
                    parsed.items() == null ? 0 : parsed.items().size(),
                    parsed.hasMore());
            return parsed;
        } catch (IllegalStateException exception) {
            throw new IllegalStateException(humanizeError(exception.getMessage()), exception);
        }
    }

    private LarkMessageSearchResult listChatMessages(
            String chatId,
            String startTime,
            String endTime,
            Integer pageSize,
            Integer pageLimit
    ) {
        int normalizedPageSize = normalize(pageSize, DEFAULT_PAGE_SIZE, 1, 50);
        int normalizedPageLimit = normalize(pageLimit, 1, 1, 40);
        List<LarkMessageSearchItem> items = new ArrayList<>();
        String pageToken = null;
        boolean hasMore = false;
        for (int page = 0; page < normalizedPageLimit; page++) {
            List<String> args = new ArrayList<>();
            args.add("im");
            args.add("+chat-messages-list");
            args.add("--as");
            args.add("user");
            if (hasText(chatId)) {
                args.add("--chat-id");
                args.add(chatId.trim());
            }
            if (hasText(startTime)) {
                args.add("--start");
                args.add(startTime.trim());
            }
            if (hasText(endTime)) {
                args.add("--end");
                args.add(endTime.trim());
            }
            args.add("--sort");
            args.add("asc");
            args.add("--page-size");
            args.add(String.valueOf(normalizedPageSize));
            if (hasText(pageToken)) {
                args.add("--page-token");
                args.add(pageToken);
            }
            args.add("--format");
            args.add("json");

            JsonNode root;
            try {
                log.info("LARK_IM_SEARCH_CLI_START mode=chat-messages-list chatId={} query='' start={} end={} pageSize={} pageLimit={} page={}",
                        safe(chatId),
                        safe(startTime),
                        safe(endTime),
                        normalizedPageSize,
                        normalizedPageLimit,
                        page + 1);
                root = cliClient.executeJson(args, null, commandTimeoutMillis());
            } catch (IllegalStateException exception) {
                throw new IllegalStateException(humanizeError(exception.getMessage()), exception);
            }
            LarkMessageSearchResult pageResult = parse(root);
            log.info("LARK_IM_SEARCH_CLI_RESULT mode=chat-messages-list chatId={} query='' itemCount={} hasMore={} pageTokenPresent={} page={}",
                    safe(chatId),
                    pageResult.items() == null ? 0 : pageResult.items().size(),
                    pageResult.hasMore(),
                    hasText(pageResult.pageToken()),
                    page + 1);
            if (pageResult.items() != null) {
                items.addAll(pageResult.items());
            }
            hasMore = pageResult.hasMore();
            pageToken = pageResult.pageToken();
            if (!hasMore || !hasText(pageToken)) {
                break;
            }
        }
        return new LarkMessageSearchResult(items, hasMore, pageToken);
    }

    public LarkMessageSearchResult parse(JsonNode root) {
        JsonNode container = root == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : root;
        JsonNode itemsNode = firstArray(container, "items", "messages");
        if (!itemsNode.isArray()) {
            itemsNode = firstArray(container.path("data"), "items", "messages");
        }
        List<LarkMessageSearchItem> items = new ArrayList<>();
        if (itemsNode.isArray()) {
            for (JsonNode item : itemsNode) {
                items.add(mapItem(item));
            }
        }
        items.sort(Comparator
                .comparing((LarkMessageSearchItem item) -> safeText(item.createTime()))
                .thenComparing(item -> safeText(item.messageId())));
        JsonNode data = container.path("data");
        return new LarkMessageSearchResult(
                items,
                firstBoolean(container, data, "has_more"),
                firstText(container, data, "page_token")
        );
    }

    private LarkMessageSearchItem mapItem(JsonNode item) {
        JsonNode sender = item.path("sender");
        JsonNode body = item.path("body");
        return new LarkMessageSearchItem(
                firstText(item, "message_id", "messageId"),
                firstText(item, "thread_id", "threadId"),
                firstText(item, "msg_type", "msgType"),
                firstText(item, "create_time", "createTime"),
                item.path("deleted").asBoolean(false),
                item.path("updated").asBoolean(false),
                firstText(item, "chat_id", "chatId"),
                firstText(item, "chat_name", "chatName"),
                firstText(sender, "id", "open_id", "openId"),
                firstText(sender, "name", "sender_name", "senderName"),
                firstText(sender, "sender_type", "senderType"),
                firstNonBlank(firstText(item, "content"), firstText(body, "content")),
                mapMentions(item.path("mentions"))
        );
    }

    private List<LarkMessageMention> mapMentions(JsonNode mentionsNode) {
        if (!mentionsNode.isArray()) {
            return List.of();
        }
        List<LarkMessageMention> mentions = new ArrayList<>();
        for (JsonNode mention : mentionsNode) {
            mentions.add(new LarkMessageMention(
                    firstText(mention, "key"),
                    firstText(mention, "id"),
                    firstText(mention, "id_type", "idType"),
                    firstText(mention, "name"),
                    firstText(mention, "tenant_key", "tenantKey")
            ));
        }
        return mentions;
    }

    private JsonNode firstArray(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isArray()) {
                return value;
            }
        }
        return com.fasterxml.jackson.databind.node.NullNode.getInstance();
    }

    private boolean firstBoolean(JsonNode left, JsonNode right, String field) {
        JsonNode value = left == null ? null : left.path(field);
        if (value != null && !value.isMissingNode() && !value.isNull()) {
            return value.asBoolean(false);
        }
        return right != null && right.path(field).asBoolean(false);
    }

    private String firstText(JsonNode left, JsonNode right, String field) {
        String value = firstText(left, field);
        return hasText(value) ? value : firstText(right, field);
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private int normalize(Integer requested, int defaultValue, int min, int max) {
        if (requested == null || requested <= 0) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, requested));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private long commandTimeoutMillis() {
        int timeoutSeconds = properties.getTimeoutSeconds();
        return timeoutSeconds <= 0 ? 0 : timeoutSeconds * 1000L;
    }

    private String humanizeError(String message) {
        if (message != null && message.contains("search:message")) {
            return "飞书消息搜索缺少 search:message 权限，请先执行：lark-cli auth login --scope \"search:message\"";
        }
        return message == null || message.isBlank() ? "飞书消息搜索失败" : message;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
