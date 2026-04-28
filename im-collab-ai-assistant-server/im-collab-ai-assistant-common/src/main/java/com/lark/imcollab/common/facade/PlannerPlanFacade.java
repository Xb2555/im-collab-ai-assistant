package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;

/**
 * @deprecated replaced by {@link PlannerFacade}
 */
@Deprecated
public interface PlannerPlanFacade {

    PlanTaskSession plan(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String taskId,
            String userFeedback
    );
}
