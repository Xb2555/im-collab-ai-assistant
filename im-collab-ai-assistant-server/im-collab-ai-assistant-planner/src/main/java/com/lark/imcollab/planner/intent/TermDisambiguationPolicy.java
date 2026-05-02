package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;

import java.util.Optional;

public interface TermDisambiguationPolicy {

    Optional<TermDisambiguationService.DisambiguationOutcome> evaluate(
            PlanTaskSession session,
            String rawInstruction,
            WorkspaceContext workspaceContext
    );
}
