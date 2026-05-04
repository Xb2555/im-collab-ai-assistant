package com.lark.imcollab.skills.lark.im;

import java.util.List;

public record LarkMessageSearchResult(
        List<LarkMessageSearchItem> items,
        boolean hasMore,
        String pageToken
) {
}
