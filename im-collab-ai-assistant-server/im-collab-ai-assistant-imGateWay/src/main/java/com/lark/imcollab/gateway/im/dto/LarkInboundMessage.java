package com.lark.imcollab.gateway.im.dto;

import com.lark.imcollab.common.model.enums.InputSourceEnum;

public record LarkInboundMessage(
        String eventId,
        String messageId,
        String chatId,
        String threadId,
        String chatType,
        String messageType,
        String content,
        String senderOpenId,
        String createTime,
        InputSourceEnum inputSource
) {
}
