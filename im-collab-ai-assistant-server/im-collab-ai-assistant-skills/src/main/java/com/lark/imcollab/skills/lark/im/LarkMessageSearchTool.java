package com.lark.imcollab.skills.lark.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class LarkMessageSearchTool {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_LIMIT = 5;
    private static final int LOW_RECALL_THRESHOLD = 5;
    private static final int MAX_EXPANDED_QUERY_COUNT = 3;
    private static final int CONTEXT_NEIGHBOR_RADIUS = 1;
    private static final DateTimeFormatter FALLBACK_LOCAL_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final LarkCliClient cliClient;
    private final LarkCliProperties properties;
    private final LarkMessageQueryExpansionService queryExpansionService;
    private final LarkMentionTargetIdentityService mentionTargetIdentityService;

    public LarkMessageSearchTool(LarkCliClient cliClient, LarkCliProperties properties) {
        this(
                cliClient,
                properties,
                (userQuery, originalQuery, startTime, endTime, maxQueries) -> List.of(),
                item -> false
        );
    }

    @Autowired
    public LarkMessageSearchTool(
            LarkCliClient cliClient,
            LarkCliProperties properties,
            LarkMessageQueryExpansionService queryExpansionService,
            LarkMentionTargetIdentityService mentionTargetIdentityService
    ) {
        this.cliClient = cliClient;
        this.properties = properties;
        this.queryExpansionService = queryExpansionService;
        this.mentionTargetIdentityService = mentionTargetIdentityService;
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
        boolean hasQuery = hasText(query);
        boolean hasTimeWindow = hasText(startTime) || hasText(endTime);

        LarkMessageSearchResult windowResult = (!hasQuery || hasTimeWindow)
                ? listChatMessages(chatId, startTime, endTime, pageSize, pageLimit)
                : null;
        LarkMessageSearchResult primaryQueryResult = hasQuery
                ? searchByKeyword(query, chatId, startTime, endTime, pageSize, pageLimit)
                : null;

        int primaryHitCount = sizeOf(primaryQueryResult);
        int filteredPrimaryHitCount = sizeOfEffective(primaryQueryResult == null ? List.of() : primaryQueryResult.items());
        ExpandedQueryPlan expandedQueryPlan = buildExpandedQueryPlan(
                query,
                startTime,
                endTime,
                primaryHitCount,
                filteredPrimaryHitCount
        );
        Map<String, LarkMessageSearchResult> expandedResults = new LinkedHashMap<>();
        for (String expandedQuery : expandedQueryPlan.expandedQueries()) {
            expandedResults.put(
                    expandedQuery,
                    searchByKeyword(expandedQuery, chatId, startTime, endTime, pageSize, pageLimit)
            );
        }
        return mergeAndRank(
                query,
                windowResult,
                primaryQueryResult,
                expandedQueryPlan,
                expandedResults
        );
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

    private ExpandedQueryPlan buildExpandedQueryPlan(
            String query,
            String startTime,
            String endTime,
            int primaryHitCount,
            int filteredPrimaryHitCount
    ) {
        if (!hasText(query)) {
            return ExpandedQueryPlan.empty();
        }
        boolean rawLowRecall = primaryHitCount < LOW_RECALL_THRESHOLD;
        boolean filteredLowRecall = filteredPrimaryHitCount < LOW_RECALL_THRESHOLD;
        if (!rawLowRecall && !filteredLowRecall) {
            return new ExpandedQueryPlan(
                    query,
                    List.of(),
                    "primary-hit-threshold-satisfied:%d>=%d,filtered-primary-hit-threshold-satisfied:%d>=%d"
                            .formatted(
                                    primaryHitCount,
                                    LOW_RECALL_THRESHOLD,
                                    filteredPrimaryHitCount,
                                    LOW_RECALL_THRESHOLD
                            )
            );
        }
        List<String> expandedQueries = queryExpansionService.expandQueries(
                query,
                query,
                startTime,
                endTime,
                MAX_EXPANDED_QUERY_COUNT
        ).stream()
                .filter(this::hasText)
                .map(String::trim)
                .filter(expandedQuery -> !normalizeText(expandedQuery).equals(normalizeText(query)))
                .distinct()
                .limit(MAX_EXPANDED_QUERY_COUNT)
                .toList();
        String triggerReason = expansionTriggerReason(primaryHitCount, filteredPrimaryHitCount);
        log.info("LARK_IM_SEARCH_EXPANSION_PLAN originalQuery='{}' primaryHitCount={} filteredPrimaryHitCount={} expandedQueries={} triggerReason={}",
                safe(query),
                primaryHitCount,
                filteredPrimaryHitCount,
                expandedQueries,
                triggerReason);
        return new ExpandedQueryPlan(
                query,
                expandedQueries,
                triggerReason
        );
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

    private LarkMessageSearchResult mergeAndRank(
            String originalQuery,
            LarkMessageSearchResult windowResult,
            LarkMessageSearchResult primaryQueryResult,
            ExpandedQueryPlan expandedQueryPlan,
            Map<String, LarkMessageSearchResult> expandedResults
    ) {
        Map<String, CandidateAccumulator> merged = new LinkedHashMap<>();
        List<LarkMessageSearchItem> windowItems = windowResult == null ? List.of() : windowResult.items();
        List<LarkMessageSearchItem> primaryItems = primaryQueryResult == null ? List.of() : primaryQueryResult.items();

        for (LarkMessageSearchItem item : windowItems) {
            accumulatorOf(merged, item).windowPrimary = true;
        }
        for (LarkMessageSearchItem item : primaryItems) {
            CandidateAccumulator accumulator = accumulatorOf(merged, item);
            accumulator.primaryQueryHit = true;
            accumulator.hitSources.add("primary-query");
        }
        int expandedHitCount = 0;
        for (Map.Entry<String, LarkMessageSearchResult> entry : expandedResults.entrySet()) {
            String expandedQuery = entry.getKey();
            LarkMessageSearchResult expandedResult = entry.getValue();
            expandedHitCount += sizeOf(expandedResult);
            for (LarkMessageSearchItem item : expandedResult == null ? List.<LarkMessageSearchItem>of() : expandedResult.items()) {
                CandidateAccumulator accumulator = accumulatorOf(merged, item);
                accumulator.matchedExpandedQueries.add(expandedQuery);
                accumulator.hitSources.add("expanded-query:" + expandedQuery);
            }
        }

        int contextExpandedCount = markContextNeighbors(windowItems, merged);
        List<RankedMessageCandidate> rankedCandidates = merged.values().stream()
                .filter(accumulator -> accumulator.message != null)
                .map(CandidateAccumulator::toRankedCandidate)
                .sorted(Comparator
                        .comparingInt(RankedMessageCandidate::score).reversed()
                        .thenComparing(candidate -> sortInstant(candidate.message().createTime()))
                        .thenComparing(candidate -> safeText(candidate.message().messageId())))
                .toList();
        List<LarkMessageSearchItem> items = rankedCandidates.stream()
                .map(RankedMessageCandidate::message)
                .toList();
        return new LarkMessageSearchResult(
                items,
                anyHasMore(windowResult, primaryQueryResult, expandedResults.values()),
                firstNonBlank(
                        primaryQueryResult == null ? null : primaryQueryResult.pageToken(),
                        windowResult == null ? null : windowResult.pageToken(),
                        firstExpandedPageToken(expandedResults)
                ),
                sizeOf(primaryQueryResult),
                sizeOfEffective(primaryQueryResult == null ? List.of() : primaryQueryResult.items()),
                sizeOf(windowResult),
                expandedQueryPlan == null ? new ExpandedQueryPlan(originalQuery, List.of(), "") : expandedQueryPlan,
                expandedHitCount,
                contextExpandedCount,
                items.size(),
                rankedCandidates
        );
    }

    private int markContextNeighbors(
            List<LarkMessageSearchItem> windowItems,
            Map<String, CandidateAccumulator> merged
    ) {
        if (windowItems == null || windowItems.isEmpty()) {
            return 0;
        }
        List<String> windowKeys = windowItems.stream()
                .map(this::dedupeKey)
                .toList();
        Map<String, Integer> windowIndexes = new LinkedHashMap<>();
        for (int index = 0; index < windowItems.size(); index++) {
            windowIndexes.putIfAbsent(windowKeys.get(index), index);
        }
        Set<String> directHitKeys = merged.entrySet().stream()
                .filter(entry -> entry.getValue().primaryQueryHit || !entry.getValue().matchedExpandedQueries.isEmpty())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int contextExpandedCount = 0;
        for (String directHitKey : directHitKeys) {
            Integer hitIndex = windowIndexes.get(directHitKey);
            if (hitIndex == null) {
                continue;
            }
            for (int delta = -CONTEXT_NEIGHBOR_RADIUS; delta <= CONTEXT_NEIGHBOR_RADIUS; delta++) {
                if (delta == 0) {
                    continue;
                }
                int neighborIndex = hitIndex + delta;
                if (neighborIndex < 0 || neighborIndex >= windowItems.size()) {
                    continue;
                }
                CandidateAccumulator accumulator = accumulatorOf(merged, windowItems.get(neighborIndex));
                if (!accumulator.contextNeighbor
                        && !accumulator.primaryQueryHit
                        && accumulator.matchedExpandedQueries.isEmpty()) {
                    contextExpandedCount++;
                }
                accumulator.contextNeighbor = true;
                accumulator.hitSources.add("context-neighbor");
            }
        }
        return contextExpandedCount;
    }

    private CandidateAccumulator accumulatorOf(
            Map<String, CandidateAccumulator> merged,
            LarkMessageSearchItem item
    ) {
        String key = dedupeKey(item);
        CandidateAccumulator accumulator = merged.computeIfAbsent(key, ignored -> new CandidateAccumulator());
        if (accumulator.message == null) {
            accumulator.message = item;
        }
        return accumulator;
    }

    private String dedupeKey(LarkMessageSearchItem item) {
        if (item == null) {
            return "";
        }
        return hasText(item.messageId())
                ? item.messageId().trim()
                : firstNonBlank(item.createTime(), "") + "|" + firstNonBlank(item.content(), "");
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

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "");
    }

    private int sizeOf(LarkMessageSearchResult result) {
        return result == null || result.items() == null ? 0 : result.items().size();
    }

    private int sizeOfEffective(List<LarkMessageSearchItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        return (int) items.stream()
                .filter(this::isEffectivePrimaryCandidate)
                .count();
    }

    private boolean isEffectivePrimaryCandidate(LarkMessageSearchItem item) {
        return item != null
                && !item.deleted()
                && hasText(item.content())
                && !"system".equalsIgnoreCase(item.msgType())
                && !"app".equalsIgnoreCase(item.senderType())
                && !"bot".equalsIgnoreCase(item.senderType())
                && !mentionTargetIdentityService.isLeadingBotMentionCommand(item);
    }

    private String expansionTriggerReason(int primaryHitCount, int filteredPrimaryHitCount) {
        List<String> reasons = new ArrayList<>();
        if (primaryHitCount < LOW_RECALL_THRESHOLD) {
            reasons.add("primary-hit-below-threshold:%d<%d".formatted(primaryHitCount, LOW_RECALL_THRESHOLD));
        }
        if (filteredPrimaryHitCount < LOW_RECALL_THRESHOLD) {
            reasons.add("filtered-primary-hit-below-threshold:%d<%d"
                    .formatted(filteredPrimaryHitCount, LOW_RECALL_THRESHOLD));
        }
        return String.join(",", reasons);
    }

    private boolean anyHasMore(
            LarkMessageSearchResult windowResult,
            LarkMessageSearchResult primaryQueryResult,
            Iterable<LarkMessageSearchResult> expandedResults
    ) {
        if (windowResult != null && windowResult.hasMore()) {
            return true;
        }
        if (primaryQueryResult != null && primaryQueryResult.hasMore()) {
            return true;
        }
        for (LarkMessageSearchResult result : expandedResults) {
            if (result != null && result.hasMore()) {
                return true;
            }
        }
        return false;
    }

    private String firstExpandedPageToken(Map<String, LarkMessageSearchResult> expandedResults) {
        if (expandedResults == null) {
            return null;
        }
        for (LarkMessageSearchResult result : expandedResults.values()) {
            if (result != null && hasText(result.pageToken())) {
                return result.pageToken();
            }
        }
        return null;
    }

    private Instant sortInstant(String value) {
        if (!hasText(value)) {
            return Instant.MAX;
        }
        String trimmed = value.trim();
        if (trimmed.matches("\\d{10}")) {
            return Instant.ofEpochSecond(Long.parseLong(trimmed));
        }
        if (trimmed.matches("\\d{13}")) {
            return Instant.ofEpochMilli(Long.parseLong(trimmed));
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed, FALLBACK_LOCAL_TIME_FORMATTER)
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
        } catch (DateTimeParseException ignored) {
        }
        return Instant.MAX;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static final class CandidateAccumulator {
        private LarkMessageSearchItem message;
        private final Set<String> hitSources = new LinkedHashSet<>();
        private final Set<String> matchedExpandedQueries = new LinkedHashSet<>();
        private boolean windowPrimary;
        private boolean primaryQueryHit;
        private boolean contextNeighbor;

        private RankedMessageCandidate toRankedCandidate() {
            int queryHitCount = (primaryQueryHit ? 1 : 0) + matchedExpandedQueries.size();
            int score = 0;
            if (primaryQueryHit) {
                score = Math.max(score, 100);
            }
            if (contextNeighbor) {
                score = Math.max(score, 60);
            }
            if (windowPrimary) {
                score = Math.max(score, 40);
            }
            score += matchedExpandedQueries.size() * 20;
            if (queryHitCount > 1) {
                score += 15;
            }
            return new RankedMessageCandidate(
                    message,
                    new ArrayList<>(hitSources),
                    new ArrayList<>(matchedExpandedQueries),
                    windowPrimary,
                    primaryQueryHit,
                    contextNeighbor,
                    score
            );
        }
    }
}
