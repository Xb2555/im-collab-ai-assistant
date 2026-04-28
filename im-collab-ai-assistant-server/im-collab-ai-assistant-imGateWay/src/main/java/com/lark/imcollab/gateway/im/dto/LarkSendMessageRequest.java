package com.lark.imcollab.gateway.im.dto;

public record LarkSendMessageRequest(
        String chatId,
        String text,
        String idempotencyKey
) {
}
