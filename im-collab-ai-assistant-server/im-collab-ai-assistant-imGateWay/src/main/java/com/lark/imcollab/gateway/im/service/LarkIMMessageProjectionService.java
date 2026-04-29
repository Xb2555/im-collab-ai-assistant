package com.lark.imcollab.gateway.im.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.im.dto.LarkRealtimeMessage;
import com.lark.imcollab.gateway.im.event.LarkMessageEvent;
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryItem;
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LarkIMMessageProjectionService {

    private static final Pattern TEMPLATE_VARIABLE_PATTERN = Pattern.compile("\\{([^{}]+)}");

    private final LarkUserProfileHydrationService userProfileHydrationService;
    private final ObjectMapper objectMapper;

    public LarkIMMessageProjectionService(
            LarkUserProfileHydrationService userProfileHydrationService,
            ObjectMapper objectMapper
    ) {
        this.userProfileHydrationService = userProfileHydrationService;
        this.objectMapper = objectMapper;
    }

    public LarkMessageHistoryResponse projectHistory(LarkMessageHistoryResponse response, String userAccessToken) {
        if (response == null) {
            return null;
        }
        List<LarkMessageHistoryItem> items = new ArrayList<>();
        for (LarkMessageHistoryItem item : response.items()) {
            items.add(projectHistoryItem(item, userAccessToken));
        }
        return new LarkMessageHistoryResponse(items, response.hasMore(), response.pageToken());
    }

    public LarkRealtimeMessage projectRealtime(LarkMessageEvent event) {
        LarkUserProfile sender = shouldHydrateSender(event == null ? null : event.messageType())
                ? userProfileHydrationService.resolveByTenantAccessToken(event.senderOpenId())
                : null;
        String content = renderContent(
                event == null ? null : event.messageType(),
                event == null ? null : event.content(),
                userProfileHydrationService::resolveByTenantAccessToken
        );
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
                event != null && event.mentionDetected()
        );
    }

    private LarkMessageHistoryItem projectHistoryItem(LarkMessageHistoryItem item, String userAccessToken) {
        LarkUserProfile sender = shouldHydrateSender(item.msgType())
                ? userProfileHydrationService.resolveByUserAccessToken(userAccessToken, item.senderId())
                : null;
        String content = renderContent(
                item.msgType(),
                item.content(),
                openId -> userProfileHydrationService.resolveByUserAccessToken(userAccessToken, openId)
        );
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
                content,
                item.mentions(),
                item.upperMessageId(),
                sender == null ? null : sender.name(),
                sender == null ? null : sender.avatarUrl()
        );
    }

    private boolean shouldHydrateSender(String messageType) {
        return "text".equalsIgnoreCase(messageType);
    }

    private String renderContent(
            String messageType,
            String content,
            Function<String, LarkUserProfile> profileResolver
    ) {
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
                text(node, "value")
        );
        if (explicitName != null) {
            return explicitName;
        }
        String openId = firstText(
                text(node, "open_id"),
                text(node, "user_id"),
                text(node, "id"),
                text(node.path("user"), "open_id"),
                text(node.path("user"), "user_id"),
                text(node.path("user"), "id")
        );
        if (openId != null) {
            return profileName(openId, profileResolver);
        }
        return null;
    }

    private String profileName(String openId, Function<String, LarkUserProfile> profileResolver) {
        LarkUserProfile profile = profileResolver.apply(openId);
        return firstText(profile == null ? null : profile.name(), openId);
    }

    private boolean looksLikeOpenId(String value) {
        return value != null && value.startsWith("ou_");
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
