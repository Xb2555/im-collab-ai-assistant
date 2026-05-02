package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.WorkspaceContext;

public record ContextCollectionOutcome(
        ContextSufficiencyResult contextResult,
        WorkspaceContext workspaceContext
) {
}
