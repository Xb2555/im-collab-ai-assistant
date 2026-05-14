package com.lark.imcollab.skills.lark.im;

import java.util.List;

public record RankedMessageCandidate(
        LarkMessageSearchItem message,
        List<String> hitSources,
        List<String> matchedExpandedQueries,
        boolean windowPrimary,
        boolean primaryQueryHit,
        boolean contextNeighbor,
        int score
) {
    public RankedMessageCandidate {
        hitSources = hitSources == null ? List.of() : List.copyOf(hitSources);
        matchedExpandedQueries = matchedExpandedQueries == null ? List.of() : List.copyOf(matchedExpandedQueries);
    }
}
