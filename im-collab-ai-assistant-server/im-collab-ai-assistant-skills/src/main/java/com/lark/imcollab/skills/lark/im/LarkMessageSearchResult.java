package com.lark.imcollab.skills.lark.im;

import java.util.List;

public record LarkMessageSearchResult(
        List<LarkMessageSearchItem> items,
        boolean hasMore,
        String pageToken,
        int primaryHitCount,
        int filteredPrimaryHitCount,
        int windowItemCount,
        ExpandedQueryPlan expandedQueryPlan,
        int expandedHitCount,
        int contextExpandedCount,
        int mergedItemCount,
        List<RankedMessageCandidate> rankedCandidates
) {
    public LarkMessageSearchResult(List<LarkMessageSearchItem> items, boolean hasMore, String pageToken) {
        this(
                items,
                hasMore,
                pageToken,
                0,
                0,
                0,
                ExpandedQueryPlan.empty(),
                0,
                0,
                items == null ? 0 : items.size(),
                List.of()
        );
    }

    public LarkMessageSearchResult {
        items = items == null ? List.of() : List.copyOf(items);
        expandedQueryPlan = expandedQueryPlan == null ? ExpandedQueryPlan.empty() : expandedQueryPlan;
        rankedCandidates = rankedCandidates == null ? List.of() : List.copyOf(rankedCandidates);
    }
}
