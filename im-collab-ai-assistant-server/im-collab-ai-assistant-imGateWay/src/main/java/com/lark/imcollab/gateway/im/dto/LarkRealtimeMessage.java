package com.lark.imcollab.gateway.im.dto;

public record LarkRealtimeMessage(
        String eventId,
        String messageId,
        String chatId,
        String chatType,
        String messageType,
        String content,
        String senderOpenId,
        String senderName,
        String senderAvatar,
        String createTime,
        boolean mentionDetected
) {
}
