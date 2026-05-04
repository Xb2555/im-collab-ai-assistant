package com.lark.imcollab.skills.lark.im;

import java.util.List;

public record LarkMessageSearchItem(
        String messageId,
        String threadId,
        String msgType,
        String createTime,
        boolean deleted,
        boolean updated,
        String chatId,
        String chatName,
        String senderId,
        String senderName,
        String senderType,
        String content,
        List<LarkMessageMention> mentions
) {
}
