package com.lark.imcollab.gateway.im.dto;

import java.util.List;

public record LarkChatListResponse(
        List<LarkChatSummary> items,
        boolean hasMore,
        String pageToken
) {
}
