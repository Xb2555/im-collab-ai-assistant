package com.lark.imcollab.gateway.im.dto;

public record LarkCreateChatResponse(
        String chatId,
        String name,
        String chatType,
        String ownerOpenId
) {
}
