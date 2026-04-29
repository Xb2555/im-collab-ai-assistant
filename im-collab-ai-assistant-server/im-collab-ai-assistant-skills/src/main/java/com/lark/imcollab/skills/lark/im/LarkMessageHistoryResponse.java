package com.lark.imcollab.skills.lark.im;

import java.util.List;

public record LarkMessageHistoryResponse(
        List<LarkMessageHistoryItem> items,
        boolean hasMore,
        String pageToken
) {
}
