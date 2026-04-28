package com.lark.imcollab.planner.facade;

import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.planner.service.PlannerConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultPlannerPlanFacade implements PlannerPlanFacade {

    private final PlannerConversationService plannerConversationService;

    @Override
    public PlanTaskSession plan(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String taskId,
            String userFeedback
    ) {
        return plannerConversationService.handlePlanRequest(rawInstruction, workspaceContext, taskId, userFeedback);
    }
}
