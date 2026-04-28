package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;

/**
 * Planner 场景的统一计划门面。
 * GUI 直连 planner 或 IM 转接到 planner 都应复用此入口语义。
 */
public interface PlannerPlanFacade {

    PlanTaskSession plan(
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String taskId,
            String userFeedback
    );
}
