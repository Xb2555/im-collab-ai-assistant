package com.lark.imcollab.gateway.im.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lark.imcollab.gateway.im.dto.LarkMessageHistoryViewResponse;
import com.lark.imcollab.gateway.im.dto.LarkRealtimeMessage;
import com.lark.imcollab.gateway.im.dto.LarkUserDisplayInfo;
import com.lark.imcollab.gateway.im.event.LarkMessageEvent;
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryItem;
import com.lark.imcollab.skills.lark.im.LarkMessageMention;
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LarkIMMessageProjectionService {

    private static final Logger log = LoggerFactory.getLogger(LarkIMMessageProjectionService.class);
    private static final Pattern TEMPLATE_VARIABLE_PATTERN = Pattern.compile("\\{([^{}]+)}");

    private final LarkUserProfileHydrationService userProfileHydrationService;
    private final ObjectMapper objectMapper;

    public LarkIMMessageProjectionService(
            LarkUserProfileHydrationService userProfileHydrationService,
            ObjectMapper objectMapper) {
        this.userProfileHydrationService = userProfileHydrationService;
        this.objectMapper = objectMapper;
    }

    public LarkMessageHistoryViewResponse projectHistory(LarkMessageHistoryResponse response, String userAccessToken) {
        if (response == null) {
            return null;
        }
        List<LarkMessageHistoryItem> items = new ArrayList<>();
        Set<String> openIds = collectOpenIds(response.items());
        Map<String, LarkUserDisplayInfo> userMap = hydrateUserMap(openIds, userAccessToken);
        for (LarkMessageHistoryItem item : response.items()) {
            items.add(projectHistoryItem(item, userMap));
        }
        return new LarkMessageHistoryViewResponse(items, response.hasMore(), response.pageToken(), userMap);
    }

    public LarkRealtimeMessage projectRealtime(LarkMessageEvent event) {
        LarkUserProfile sender = shouldHydrateSender(event == null ? null : event.messageType())
                ? userProfileHydrationService.resolveByTenantAccessToken(event.senderOpenId())
                : null;
        String content = renderContent(
                event == null ? null : event.messageType(),
                event == null ? null : event.content(),
                userProfileHydrationService::resolveByTenantAccessToken);
        return new LarkRealtimeMessage(
                event == null ? null : event.eventId(),
                event == null ? null : event.messageId(),
                event == null ? null : event.chatId(),
                event == null ? null : event.chatType(),
                event == null ? null : event.messageType(),
                content,
                event == null ? null : event.senderOpenId(),
                sender == null ? null : sender.name(),
                sender == null ? null : sender.avatarUrl(),
                event == null ? null : event.createTime(),
                event != null && event.mentionDetected());
    }

    private LarkMessageHistoryItem projectHistoryItem(
            LarkMessageHistoryItem item,
            Map<String, LarkUserDisplayInfo> userMap) {
        LarkUserDisplayInfo sender = shouldHydrateSender(item.msgType())
                ? userMap.get(normalizeOpenId(item.senderId()))
                : null;
        String resolvedSenderName = firstText(sender == null ? null : sender.name(), item.senderName());
        String resolvedSenderAvatar = firstText(sender == null ? null : sender.avatar(), item.senderAvatar());
        return new LarkMessageHistoryItem(
                item.messageId(),
                item.rootId(),
                item.parentId(),
                item.threadId(),
                item.msgType(),
                item.createTime(),
                item.updateTime(),
                item.deleted(),
                item.updated(),
                item.chatId(),
                item.senderId(),
                item.senderIdType(),
                item.senderType(),
                item.tenantKey(),
                normalizeHistoryContent(item.msgType(), item.content(), userMap),
                item.mentions(),
                item.upperMessageId(),
                resolvedSenderName,
                resolvedSenderAvatar);
    }

    private Set<String> collectOpenIds(List<LarkMessageHistoryItem> items) {
        Set<String> openIds = new LinkedHashSet<>();
        if (items == null) {
            return openIds;
        }
        for (LarkMessageHistoryItem item : items) {
            if (item == null) {
                continue;
            }
            addOpenId(openIds, item.senderId());
            if (item.mentions() != null) {
                for (LarkMessageMention mention : item.mentions()) {
                    if (mention != null) {
                        addOpenId(openIds, mention.id());
                    }
                }
            }
            collectOpenIdsFromContent(openIds, item.content());
        }
        return openIds;
    }

    private void collectOpenIdsFromContent(Set<String> openIds, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        try {
            collectOpenIdsFromJson(openIds, objectMapper.readTree(content));
        } catch (IOException ignored) {
            // Message content can be arbitrary user text. Only JSON system/card payloads
            // are scanned for ids.
        }
    }

    private void collectOpenIdsFromJson(Set<String> openIds, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            addOpenId(openIds, node.asText());
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectOpenIdsFromJson(openIds, child));
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectOpenIdsFromJson(openIds, entry.getValue()));
        }
    }

    private Map<String, LarkUserDisplayInfo> hydrateUserMap(Set<String> openIds, String userAccessToken) {
        Map<String, LarkUserDisplayInfo> userMap = new LinkedHashMap<>();
        if (openIds == null) {
            return userMap;
        }
        for (String openId : openIds) {
            LarkUserProfile profile = null;
            if (userAccessToken != null && !userAccessToken.isBlank()) {
                profile = userProfileHydrationService.resolveByUserAccessToken(userAccessToken, openId);
            }
            if (!hasDisplayData(profile)) {
                LarkUserProfile tenantProfile = userProfileHydrationService.resolveByTenantAccessToken(openId);
                if (hasDisplayData(tenantProfile)) {
                    log.debug("Hydrated Lark history user profile with tenant token fallback: openId={}, userTokenPresent={}",
                            openId, userAccessToken != null && !userAccessToken.isBlank());
                    profile = tenantProfile;
                }
            }
            userMap.put(openId, new LarkUserDisplayInfo(
                    profile == null ? null : profile.name(),
                    profile == null ? null : profile.avatarUrl()
            ));
        }
        return userMap;
    }

    private boolean hasDisplayData(LarkUserProfile profile) {
        return profile != null
                && ((profile.name() != null && !profile.name().isBlank())
                || (profile.avatarUrl() != null && !profile.avatarUrl().isBlank()));
    }

    private void addOpenId(Set<String> openIds, String value) {
        String normalized = normalizeOpenId(value);
        if (normalized != null) {
            openIds.add(normalized);
        }
    }

    private boolean shouldHydrateSender(String messageType) {
        return "text".equalsIgnoreCase(messageType);
    }

    private String normalizeHistoryContent(
            String messageType,
            String content,
            Map<String, LarkUserDisplayInfo> userMap) {
        if (!"system".equalsIgnoreCase(messageType)) {
            return content;
        }
        if (content == null || content.isBlank()) {
            return content;
        }
        try {
            JsonNode rootNode = objectMapper.readTree(content);
            if (!(rootNode instanceof ObjectNode root)) {
                return content;
            }
            String template = firstText(text(root, "template"), text(root, "text"));
            if (template == null) {
                return content;
            }
            ObjectNode variablesNode = root.with("variables");
            Matcher matcher = TEMPLATE_VARIABLE_PATTERN.matcher(template);
            while (matcher.find()) {
                String variableName = matcher.group(1);
                JsonNode variableNode = findVariable(root, variableName);
                upsertNormalizedVariable(variablesNode, variableName, variableNode, userMap);
            }
            return objectMapper.writeValueAsString(root);
        } catch (IOException exception) {
            return content;
        }
    }

    private void upsertNormalizedVariable(
            ObjectNode variablesNode,
            String variableName,
            JsonNode variableNode,
            Map<String, LarkUserDisplayInfo> userMap) {
        if (variablesNode == null || variableName == null || variableName.isBlank()) {
            return;
        }
        ArrayNode normalized = normalizeVariableToArray(variableNode, userMap);
        if (normalized == null || normalized.isEmpty()) {
            return;
        }
        variablesNode.set(variableName, normalized);
    }

    private ArrayNode normalizeVariableToArray(JsonNode variableNode, Map<String, LarkUserDisplayInfo> userMap) {
        if (variableNode == null || variableNode.isMissingNode() || variableNode.isNull()) {
            return null;
        }
        ArrayNode result = objectMapper.createArrayNode();
        if (variableNode.isArray()) {
            for (JsonNode item : variableNode) {
                appendNormalizedValue(result, item, userMap);
            }
        } else {
            appendNormalizedValue(result, variableNode, userMap);
        }
        return result;
    }

    private void appendNormalizedValue(ArrayNode target, JsonNode node, Map<String, LarkUserDisplayInfo> userMap) {
        if (target == null || node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        String value = extractVariableValue(node, userMap);
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private String extractVariableValue(JsonNode node, Map<String, LarkUserDisplayInfo> userMap) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (looksLikeOpenId(value)) {
                LarkUserDisplayInfo user = userMap == null ? null : userMap.get(normalizeOpenId(value));
                return firstText(user == null ? null : user.name(), value);
            }
            return value;
        }
        if (!node.isObject()) {
            return node.asText(null);
        }
        String openId = firstText(
                text(node, "open_id"),
                text(node, "user_id"),
                text(node, "id"),
                text(node.path("user"), "open_id"),
                text(node.path("user"), "user_id"),
                text(node.path("user"), "id"));
        if (openId != null) {
            LarkUserDisplayInfo user = userMap == null ? null : userMap.get(normalizeOpenId(openId));
            return firstText(user == null ? null : user.name(), openId);
        }
        return firstText(
                text(node, "name"),
                text(node, "user_name"),
                text(node, "display_name"),
                text(node, "text"),
                text(node, "content"),
                text(node, "value"));
    }

    private String renderContent(
            String messageType,
            String content,
            Function<String, LarkUserProfile> profileResolver) {
        if (!"system".equalsIgnoreCase(messageType)) {
            return content;
        }
        return renderSystemContent(content, profileResolver);
    }

    private String renderSystemContent(String content, Function<String, LarkUserProfile> profileResolver) {
        if (content == null || content.isBlank()) {
            return content;
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            String template = firstText(text(root, "template"), text(root, "text"));
            if (template == null) {
                return content;
            }
            Matcher matcher = TEMPLATE_VARIABLE_PATTERN.matcher(template);
            StringBuffer rendered = new StringBuffer();
            while (matcher.find()) {
                String variableName = matcher.group(1);
                String replacement = renderVariable(findVariable(root, variableName), profileResolver);
                matcher.appendReplacement(rendered, Matcher.quoteReplacement(firstText(replacement, "某人")));
            }
            matcher.appendTail(rendered);
            return rendered.toString();
        } catch (IOException exception) {
            return content;
        }
    }

    private JsonNode findVariable(JsonNode root, String variableName) {
        if (root == null || variableName == null) {
            return null;
        }
        JsonNode direct = root.get(variableName);
        if (direct != null) {
            return direct;
        }
        JsonNode variables = root.path("variables").get(variableName);
        if (variables != null) {
            return variables;
        }
        JsonNode params = root.path("params").get(variableName);
        if (params != null) {
            return params;
        }
        return root.path("data").get(variableName);
    }

    private String renderVariable(JsonNode node, Function<String, LarkUserProfile> profileResolver) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String rendered = renderVariable(item, profileResolver);
                if (rendered != null && !rendered.isBlank()) {
                    values.add(rendered);
                }
            }
            return values.isEmpty() ? null : String.join("、", values);
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (looksLikeOpenId(value)) {
                return profileName(value, profileResolver);
            }
            return value;
        }
        if (!node.isObject()) {
            return node.asText(null);
        }

        String explicitName = firstText(
                text(node, "name"),
                text(node, "user_name"),
                text(node, "display_name"),
                text(node, "text"),
                text(node, "content"),
                text(node, "value"));
        if (explicitName != null) {
            return explicitName;
        }
        String openId = firstText(
                text(node, "open_id"),
                text(node, "user_id"),
                text(node, "id"),
                text(node.path("user"), "open_id"),
                text(node.path("user"), "user_id"),
                text(node.path("user"), "id"));
        if (openId != null) {
            return profileName(openId, profileResolver);
        }
        return null;
    }

    private String profileName(String openId, Function<String, LarkUserProfile> profileResolver) {
        LarkUserProfile profile = profileResolver.apply(openId);
        return firstText(profile == null ? null : profile.name(), openId);
    }

    private LarkUserProfile toUserProfile(LarkUserDisplayInfo displayInfo) {
        if (displayInfo == null) {
            return null;
        }
        return new LarkUserProfile(null, displayInfo.name(), displayInfo.avatar());
    }

    private boolean looksLikeOpenId(String value) {
        return normalizeOpenId(value) != null;
    }

    private String normalizeOpenId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.startsWith("ou_") ? normalized : null;
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull() || field.asText().isBlank()) {
            return null;
        }
        return field.asText();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
