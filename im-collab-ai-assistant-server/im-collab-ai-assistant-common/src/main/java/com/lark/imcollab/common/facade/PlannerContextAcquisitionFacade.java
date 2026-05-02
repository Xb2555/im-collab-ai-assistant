package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.ContextAcquisitionPlan;
import com.lark.imcollab.common.model.entity.ContextAcquisitionResult;
import com.lark.imcollab.common.model.entity.WorkspaceContext;

public interface PlannerContextAcquisitionFacade {

    ContextAcquisitionResult acquire(
            ContextAcquisitionPlan plan,
            WorkspaceContext workspaceContext,
            String rawInstruction
    );
}
