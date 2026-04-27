package com.lark.imcollab.skills.lark.event;

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
