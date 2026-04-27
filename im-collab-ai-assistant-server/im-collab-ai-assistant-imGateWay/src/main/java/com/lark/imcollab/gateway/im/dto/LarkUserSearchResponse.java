package com.lark.imcollab.gateway.im.dto;

import java.util.List;

public record LarkUserSearchResponse(
        List<LarkUserSummary> items,
        boolean hasMore,
        String pageToken
) {
}
