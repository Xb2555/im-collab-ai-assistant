package com.lark.imcollab.gateway.im.dto;

public record LarkSendMessageResponse(
        String messageId,
        String chatId,
        String createTime
) {
}
