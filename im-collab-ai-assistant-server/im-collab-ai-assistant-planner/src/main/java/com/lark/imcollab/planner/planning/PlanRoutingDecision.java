package com.lark.imcollab.planner.planning;

import java.util.List;

public record PlanRoutingDecision(
        PlanRoute route,
        boolean needsContextCollection,
        boolean needsClarification,
        boolean allowsFastPlan,
        List<String> reasons
) {
}
