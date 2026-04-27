package com.lark.imcollab.planner.facade;

import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.planner.service.SupervisorPlannerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultPlannerPlanFacade implements PlannerPlanFacade {

    private final SupervisorPlannerService supervisorPlannerService;

    @Override
    public PlanTaskSession plan(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String taskId,
            String userFeedback
    ) {
        return supervisorPlannerService.plan(rawInstruction, workspaceContext, taskId, userFeedback);
    }
}
