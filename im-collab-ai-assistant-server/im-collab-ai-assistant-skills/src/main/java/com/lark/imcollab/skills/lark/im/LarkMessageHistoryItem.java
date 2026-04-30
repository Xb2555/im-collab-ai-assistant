package com.lark.imcollab.skills.lark.im;

import java.util.List;

public record LarkMessageHistoryItem(
        String messageId,
        String rootId,
        String parentId,
        String threadId,
        String msgType,
        String createTime,
        String updateTime,
        boolean deleted,
        boolean updated,
        String chatId,
        String senderId,
        String senderIdType,
        String senderType,
        String tenantKey,
        String content,
        List<LarkMessageMention> mentions,
        String upperMessageId,
        String senderName,
        String senderAvatar
) {
}
