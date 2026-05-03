package com.lark.imcollab.gateway.im.dto;

import com.lark.imcollab.skills.lark.im.LarkMessageHistoryItem;

import java.util.List;
import java.util.Map;

public record LarkMessageHistoryViewResponse(
        List<LarkMessageHistoryItem> items,
        boolean hasMore,
        String pageToken,
        Map<String, LarkUserDisplayInfo> userMap
) {
}
