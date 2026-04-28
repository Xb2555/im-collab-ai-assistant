package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.domain.Conversation;
import com.lark.imcollab.common.domain.Task;

public interface PlannerFacade {
    Task plan(Conversation conversation);
    Task replan(String taskId, String userFeedback);
}
