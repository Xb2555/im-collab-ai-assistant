package com.lark.imcollab.gateway.im.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.dto.LarkAuthenticatedSession;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;
import com.lark.imcollab.gateway.auth.service.LarkOAuthService;
import com.lark.imcollab.gateway.im.client.LarkOpenApiClient;
import com.lark.imcollab.gateway.im.dto.LarkChatListResponse;
import com.lark.imcollab.gateway.im.dto.LarkChatShareLinkRequest;
import com.lark.imcollab.gateway.im.dto.LarkChatShareLinkResponse;
import com.lark.imcollab.gateway.im.dto.LarkChatSummary;
import com.lark.imcollab.gateway.im.dto.LarkCreateChatRequest;
import com.lark.imcollab.gateway.im.dto.LarkCreateChatResponse;
import com.lark.imcollab.gateway.im.dto.LarkInviteChatMembersRequest;
import com.lark.imcollab.gateway.im.dto.LarkInviteChatMembersResponse;
import com.lark.imcollab.gateway.im.dto.LarkSendMessageRequest;
import com.lark.imcollab.gateway.im.dto.LarkSendMessageResponse;
import com.lark.imcollab.gateway.im.dto.LarkUserSearchResponse;
import com.lark.imcollab.gateway.im.dto.LarkUserSummary;
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryMapper;
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class LarkIMChatService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_MESSAGE_HISTORY_PAGE_SIZE = 50;
    private static final int TENANT_CHAT_PAGE_SIZE = 100;
    private static final int MAX_BOT_CHAT_PAGES = 20;
    private static final int MAX_FILTER_SCAN_USER_PAGES = 20;

    private final LarkOAuthService oauthService;
    private final LarkOpenApiClient openApiClient;
    private final ObjectMapper objectMapper;

    public LarkIMChatService(
            LarkOAuthService oauthService,
            LarkOpenApiClient openApiClient,
            ObjectMapper objectMapper
    ) {
        this.oauthService = oauthService;
        this.openApiClient = openApiClient;
        this.objectMapper = objectMapper;
    }

    public LarkChatListResponse listChats(
            String authorization,
            Integer pageSize,
            String pageToken,
            String sortType,
            boolean containsCurrentBot
    ) {
        LarkAuthenticatedSession session = requireSession(authorization);
        int normalizedPageSize = normalizePageSize(pageSize);
        String normalizedSortType = normalizeOptional(sortType, "ByCreateTimeAsc");
        if (!containsCurrentBot) {
            JsonNode data = openApiClient.get(
                    "/open-apis/im/v1/chats",
                    chatListQuery(normalizedPageSize, pageToken, normalizedSortType),
                    session.accessToken()
            );
            return new LarkChatListResponse(
                    readChatSummaries(data),
                    data.path("has_more").asBoolean(false),
                    optionalText(data, "page_token")
            );
        }

        LinkedHashSet<String> botChatIds = fetchCurrentBotChatIds(normalizedSortType);
        List<LarkChatSummary> items = new ArrayList<>();
        String currentPageToken = pageToken;
        boolean hasMore = false;
        String nextPageToken = null;
        int scannedPages = 0;
        while (items.size() < normalizedPageSize && scannedPages < MAX_FILTER_SCAN_USER_PAGES) {
            JsonNode data = openApiClient.get(
                    "/open-apis/im/v1/chats",
                    chatListQuery(normalizedPageSize, currentPageToken, normalizedSortType),
                    session.accessToken()
            );
            for (LarkChatSummary item : readChatSummaries(data)) {
                if (item.chatId() != null && botChatIds.contains(item.chatId())) {
                    items.add(item);
                    if (items.size() == normalizedPageSize) {
                        break;
                    }
                }
            }
            scannedPages++;
            hasMore = data.path("has_more").asBoolean(false);
            nextPageToken = optionalText(data, "page_token");
            if (!hasMore) {
                break;
            }
            currentPageToken = nextPageToken;
        }
        return new LarkChatListResponse(
                items,
                hasMore,
                hasMore ? nextPageToken : null
        );
    }

    public LarkChatListResponse listChats(String authorization, Integer pageSize, String pageToken, String sortType) {
        return listChats(authorization, pageSize, pageToken, sortType, false);
    }

    public LarkSendMessageResponse sendMessage(String authorization, LarkSendMessageRequest request) {
        LarkAuthenticatedSession session = requireSession(authorization);
        LarkSendMessageRequest normalizedRequest = requireRequest(request, "request");
        String normalizedChatId = requireValue(normalizedRequest.chatId(), "chatId");
        String text = requireValue(normalizedRequest.text(), "text");

        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("receive_id_type", "chat_id");
        putIfPresent(queryParams, "uuid", normalizedRequest.idempotencyKey());

        JsonNode data = openApiClient.post(
                "/open-apis/im/v1/messages",
                queryParams,
                Map.of(
                        "receive_id", normalizedChatId,
                        "msg_type", "text",
                        "content", textContent(text)
                ),
                session.accessToken()
        );
        return new LarkSendMessageResponse(
                optionalText(data, "message_id"),
                optionalText(data, "chat_id"),
                optionalText(data, "create_time")
        );
    }

    public LarkCreateChatResponse createChat(String authorization, LarkCreateChatRequest request) {
        LarkAuthenticatedSession session = requireSession(authorization);
        LarkCreateChatRequest normalizedRequest = requireRequest(request, "request");
        String name = requireValue(normalizedRequest.name(), "name");
        String ownerOpenId = requireOpenId(session.user());
        LinkedHashSet<String> userOpenIds = new LinkedHashSet<>();
        if (normalizedRequest.userOpenIds() != null) {
            for (String userOpenId : normalizedRequest.userOpenIds()) {
                userOpenIds.add(requireValue(userOpenId, "userOpenId"));
            }
        }
        userOpenIds.add(ownerOpenId);
        if (userOpenIds.size() > 50) {
            throw new IllegalArgumentException("userOpenIds must not contain more than 50 users");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        putBodyIfPresent(body, "description", normalizedRequest.description());
        body.put("chat_type", normalizeOptional(normalizedRequest.chatType(), "private"));
        body.put("chat_mode", "group");
        body.put("owner_id", ownerOpenId);
        body.put("user_id_list", new ArrayList<>(userOpenIds));

        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("user_id_type", "open_id");
        queryParams.put("set_bot_manager", "true");
        putIfPresent(queryParams, "uuid", normalizedRequest.uuid());

        JsonNode data = openApiClient.postWithTenantToken("/open-apis/im/v1/chats", queryParams, body);
        return new LarkCreateChatResponse(
                optionalText(data, "chat_id"),
                optionalText(data, "name"),
                optionalText(data, "chat_type"),
                optionalText(data, "owner_id")
        );
    }

    public LarkUserSearchResponse searchUsers(String authorization, String query, Integer pageSize, String pageToken) {
        LarkAuthenticatedSession session = requireSession(authorization);
        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("query", requireValue(query, "query"));
        queryParams.put("page_size", String.valueOf(normalizePageSize(pageSize)));
        putIfPresent(queryParams, "page_token", pageToken);

        JsonNode data = openApiClient.get("/open-apis/search/v1/user", queryParams, session.accessToken());
        List<LarkUserSummary> items = new ArrayList<>();
        for (JsonNode user : firstArray(data, "users", "items")) {
            items.add(new LarkUserSummary(
                    optionalText(user, "open_id"),
                    optionalText(user, "user_id"),
                    optionalText(user, "union_id"),
                    optionalText(user, "name"),
                    optionalText(user, "en_name"),
                    optionalText(user, "email"),
                    firstNonBlank(optionalText(user, "avatar_url"), optionalText(user, "avatar"))
            ));
        }
        return new LarkUserSearchResponse(
                items,
                data.path("has_more").asBoolean(false),
                optionalText(data, "page_token")
        );
    }

    public LarkInviteChatMembersResponse inviteMembers(
            String authorization,
            LarkInviteChatMembersRequest request
    ) {
        LarkAuthenticatedSession session = requireSession(authorization);
        LarkInviteChatMembersRequest normalizedRequest = requireRequest(request, "request");
        String normalizedChatId = requireValue(normalizedRequest.chatId(), "chatId");
        if (normalizedRequest.userOpenIds() == null || normalizedRequest.userOpenIds().isEmpty()) {
            throw new IllegalArgumentException("userOpenIds must be provided");
        }
        List<String> userOpenIds = normalizedRequest.userOpenIds().stream()
                .map(value -> requireValue(value, "userOpenId"))
                .toList();
        if (userOpenIds.size() > 50) {
            throw new IllegalArgumentException("userOpenIds must not contain more than 50 users");
        }

        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("member_id_type", "open_id");
        queryParams.put("succeed_type", "1");

        JsonNode data = openApiClient.post(
                "/open-apis/im/v1/chats/" + normalizedChatId + "/members",
                queryParams,
                Map.of("id_list", userOpenIds),
                session.accessToken()
        );
        return new LarkInviteChatMembersResponse(
                textList(data.path("invalid_id_list")),
                textList(data.path("not_existed_id_list")),
                textList(data.path("pending_approval_id_list"))
        );
    }

    public LarkChatShareLinkResponse createShareLink(
            String authorization,
            String chatId,
            LarkChatShareLinkRequest request
    ) {
        LarkAuthenticatedSession session = requireSession(authorization);
        String normalizedChatId = requireValue(chatId, "chatId");
        Map<String, Object> body = new LinkedHashMap<>();
        if (request != null) {
            putBodyIfPresent(body, "validity_period", normalizeShareLinkValidityPeriod(request.validityPeriod()));
        }

        JsonNode data = openApiClient.post(
                "/open-apis/im/v1/chats/" + normalizedChatId + "/link",
                Map.of(),
                body,
                session.accessToken()
        );
        return new LarkChatShareLinkResponse(
                optionalText(data, "share_link"),
                optionalText(data, "expire_time"),
                data.path("is_permanent").asBoolean(false)
        );
    }

    public LarkMessageHistoryResponse fetchMessageHistory(
            String authorization,
            String containerIdType,
            String containerId,
            String startTime,
            String endTime,
            String sortType,
            Integer pageSize,
            String pageToken,
            String cardMsgContentType
    ) {
        LarkAuthenticatedSession session = requireSession(authorization);
        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("container_id_type", normalizeContainerIdType(containerIdType));
        queryParams.put("container_id", requireValue(containerId, "containerId"));
        putIfPresent(queryParams, "start_time", startTime);
        putIfPresent(queryParams, "end_time", endTime);
        putIfPresent(queryParams, "sort_type", sortType);
        if (pageSize != null) {
            queryParams.put("page_size", String.valueOf(normalizeMessageHistoryPageSize(pageSize)));
        }
        putIfPresent(queryParams, "page_token", pageToken);
        putIfPresent(queryParams, "card_msg_content_type", cardMsgContentType);

        JsonNode data = openApiClient.get("/open-apis/im/v1/messages", queryParams, session.accessToken());
        return LarkMessageHistoryMapper.fromData(data);
    }

    private LarkAuthenticatedSession requireSession(String authorization) {
        return oauthService.resolveAuthenticatedSessionByBusinessToken(extractBearerToken(authorization))
                .orElseThrow(() -> new LarkIMUnauthorizedException("Unauthorized"));
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        return authorization.substring(prefix.length()).trim();
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and 100");
        }
        return pageSize;
    }

    private String normalizeContainerIdType(String containerIdType) {
        String normalized = requireValue(containerIdType, "containerIdType");
        if (!"chat".equals(normalized) && !"thread".equals(normalized)) {
            throw new IllegalArgumentException("containerIdType must be chat or thread");
        }
        return normalized;
    }

    private int normalizeMessageHistoryPageSize(int pageSize) {
        if (pageSize < 1 || pageSize > MAX_MESSAGE_HISTORY_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and 50");
        }
        return pageSize;
    }

    private String normalizeShareLinkValidityPeriod(String validityPeriod) {
        if (validityPeriod == null || validityPeriod.isBlank()) {
            return null;
        }
        String normalized = validityPeriod.trim();
        if (!"week".equals(normalized) && !"year".equals(normalized) && !"permanently".equals(normalized)) {
            throw new IllegalArgumentException("validityPeriod must be week, year, or permanently");
        }
        return normalized;
    }

    private LinkedHashMap<String, String> chatListQuery(int pageSize, String pageToken, String sortType) {
        LinkedHashMap<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("page_size", String.valueOf(pageSize));
        putIfPresent(queryParams, "page_token", pageToken);
        queryParams.put("sort_type", sortType);
        queryParams.put("user_id_type", "open_id");
        return queryParams;
    }

    private LinkedHashSet<String> fetchCurrentBotChatIds(String sortType) {
        LinkedHashSet<String> chatIds = new LinkedHashSet<>();
        String pageToken = null;
        for (int page = 0; page < MAX_BOT_CHAT_PAGES; page++) {
            JsonNode data = openApiClient.getWithTenantToken(
                    "/open-apis/im/v1/chats",
                    chatListQuery(TENANT_CHAT_PAGE_SIZE, pageToken, sortType)
            );
            for (JsonNode item : data.path("items")) {
                String chatId = optionalText(item, "chat_id");
                if (chatId != null) {
                    chatIds.add(chatId);
                }
            }
            if (!data.path("has_more").asBoolean(false)) {
                break;
            }
            pageToken = optionalText(data, "page_token");
            if (pageToken == null) {
                break;
            }
        }
        return chatIds;
    }

    private List<LarkChatSummary> readChatSummaries(JsonNode data) {
        List<LarkChatSummary> items = new ArrayList<>();
        for (JsonNode item : data.path("items")) {
            items.add(new LarkChatSummary(
                    optionalText(item, "chat_id"),
                    optionalText(item, "name")
            ));
        }
        return items;
    }

    private String requireOpenId(LarkOAuthUserResponse user) {
        return requireValue(user == null ? null : user.openId(), "current user openId");
    }

    private String requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
        return value.trim();
    }

    private <T> T requireRequest(T request, String fieldName) {
        if (request == null) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
        return request;
    }

    private void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    private void putBodyIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    private String normalizeOptional(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String textContent(String text) {
        try {
            return objectMapper.writeValueAsString(Map.of("text", text));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize text message content", exception);
        }
    }

    private Iterable<JsonNode> firstArray(JsonNode root, String firstFieldName, String secondFieldName) {
        JsonNode first = root.path(firstFieldName);
        if (first.isArray()) {
            return first;
        }
        JsonNode second = root.path(secondFieldName);
        if (second.isArray()) {
            return second;
        }
        return List.of();
    }

    private List<String> textList(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (!array.isArray()) {
            return result;
        }
        for (JsonNode item : array) {
            if (!item.isNull() && !item.asText().isBlank()) {
                result.add(item.asText());
            }
        }
        return result;
    }

    private String optionalText(JsonNode root, String fieldName) {
        JsonNode field = root.path(fieldName);
        if (field.isMissingNode() || field.isNull() || field.asText().isBlank()) {
            return null;
        }
        return field.asText();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
