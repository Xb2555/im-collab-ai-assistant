package com.lark.imcollab.gateway.im.event;

public record LarkMessageEvent(
        String eventId,
        String messageId,
        String chatId,
        String chatType,
        String messageType,
        String content,
        String senderOpenId,
        String createTime,
        boolean mentionDetected
) {
}
