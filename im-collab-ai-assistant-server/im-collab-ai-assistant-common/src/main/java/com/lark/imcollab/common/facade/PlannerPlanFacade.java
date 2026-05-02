package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;

public interface PlannerPlanFacade {

    PlanTaskSession plan(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String taskId,
            String userFeedback
    );
}
