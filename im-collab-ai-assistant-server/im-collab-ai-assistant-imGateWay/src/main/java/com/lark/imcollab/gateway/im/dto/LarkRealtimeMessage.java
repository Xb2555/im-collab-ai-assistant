package com.lark.imcollab.gateway.im.dto;

public record LarkRealtimeMessage(
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
