package com.lark.imcollab.gateway.im.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.model.Header;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1Data;
import com.lark.oapi.service.im.v1.model.UserId;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
public class LarkMessageEventMapper {

    static final String MESSAGE_RECEIVE_EVENT = "im.message.receive_v1";

    private final ObjectMapper objectMapper;

    public LarkMessageEventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<LarkMessageEvent> fromSdkEvent(P2MessageReceiveV1 root) {
        if (root == null) {
            return Optional.empty();
        }

        Header header = root.getHeader();
        if (header != null && hasText(header.getEventType())
                && !MESSAGE_RECEIVE_EVENT.equals(header.getEventType())) {
            return Optional.empty();
        }

        P2MessageReceiveV1Data event = root.getEvent();
        EventMessage message = event == null ? null : event.getMessage();
        if (message == null) {
            return Optional.empty();
        }

        EventSender sender = event.getSender();
        UserId senderId = sender == null ? null : sender.getSenderId();
        String content = parseMessageContent(message.getContent());
        boolean mentionDetected = hasMentions(message.getMentions())
                || containsAtMarkup(content)
                || containsAtMarkup(message.getContent());

        if (!hasText(message.getMessageId())) {
            return Optional.empty();
        }
        if (!isPrivateChat(message.getChatType()) && !mentionDetected) {
            return Optional.empty();
        }

        return Optional.of(new LarkMessageEvent(
                header == null ? null : header.getEventId(),
                message.getMessageId(),
                message.getChatId(),
                message.getChatType(),
                message.getMessageType(),
                content,
                senderId == null ? null : senderId.getOpenId(),
                firstText(message.getCreateTime(), header == null ? null : header.getCreateTime()),
                mentionDetected
        ));
    }

    private boolean isPrivateChat(String chatType) {
        return "p2p".equalsIgnoreCase(chatType);
    }

    private boolean hasMentions(MentionEvent[] mentions) {
        return mentions != null && mentions.length > 0;
    }

    private boolean containsAtMarkup(String value) {
        return value != null && value.contains("<at ");
    }

    private String parseMessageContent(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return rawContent;
        }
        try {
            JsonNode parsed = objectMapper.readTree(rawContent);
            String text = firstText(nodeText(parsed.path("text")), nodeText(parsed.path("content")));
            return text == null ? rawContent : text;
        } catch (IOException ignored) {
            return rawContent;
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String nodeText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
