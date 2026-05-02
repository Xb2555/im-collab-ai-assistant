package com.lark.imcollab.gateway.im.event;

public record LarkMessageEvent(
        String eventId,
        String messageId,
        String chatId,
        String threadId,
        String chatType,
        String messageType,
        String content,
        String senderOpenId,
        String senderType,
        String createTime,
        boolean mentionDetected
) {
    public LarkMessageEvent(
            String eventId,
            String messageId,
            String chatId,
            String threadId,
            String chatType,
            String messageType,
            String content,
            String senderOpenId,
            String createTime,
            boolean mentionDetected
    ) {
        this(eventId, messageId, chatId, threadId, chatType, messageType, content, senderOpenId, null, createTime, mentionDetected);
    }
}
