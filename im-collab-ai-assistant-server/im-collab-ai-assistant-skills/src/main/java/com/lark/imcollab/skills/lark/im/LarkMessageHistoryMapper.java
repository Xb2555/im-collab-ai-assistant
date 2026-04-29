package com.lark.imcollab.skills.lark.im;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

public final class LarkMessageHistoryMapper {

    private LarkMessageHistoryMapper() {
    }

    public static LarkMessageHistoryResponse fromData(JsonNode data) {
        JsonNode source = data == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : data;
        List<LarkMessageHistoryItem> items = new ArrayList<>();
        JsonNode itemNodes = source.path("items");
        if (itemNodes.isArray()) {
            for (JsonNode itemNode : itemNodes) {
                items.add(mapItem(itemNode));
            }
        }
        return new LarkMessageHistoryResponse(
                items,
                source.path("has_more").asBoolean(false),
                optionalText(source, "page_token")
        );
    }

    private static LarkMessageHistoryItem mapItem(JsonNode item) {
        JsonNode sender = item.path("sender");
        return new LarkMessageHistoryItem(
                optionalText(item, "message_id"),
                optionalText(item, "root_id"),
                optionalText(item, "parent_id"),
                optionalText(item, "thread_id"),
                optionalText(item, "msg_type"),
                optionalText(item, "create_time"),
                optionalText(item, "update_time"),
                item.path("deleted").asBoolean(false),
                item.path("updated").asBoolean(false),
                optionalText(item, "chat_id"),
                optionalText(sender, "id"),
                optionalText(sender, "id_type"),
                optionalText(sender, "sender_type"),
                optionalText(sender, "tenant_key"),
                optionalText(item.path("body"), "content"),
                mapMentions(item.path("mentions")),
                optionalText(item, "upper_message_id"),
                null,
                null
        );
    }

    private static List<LarkMessageMention> mapMentions(JsonNode mentionsNode) {
        if (!mentionsNode.isArray()) {
            return List.of();
        }
        List<LarkMessageMention> mentions = new ArrayList<>();
        for (JsonNode mention : mentionsNode) {
            mentions.add(new LarkMessageMention(
                    optionalText(mention, "key"),
                    optionalText(mention, "id"),
                    optionalText(mention, "id_type"),
                    optionalText(mention, "name"),
                    optionalText(mention, "tenant_key")
            ));
        }
        return mentions;
    }

    private static String optionalText(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull() || field.asText().isBlank()) {
            return null;
        }
        return field.asText();
    }
}
