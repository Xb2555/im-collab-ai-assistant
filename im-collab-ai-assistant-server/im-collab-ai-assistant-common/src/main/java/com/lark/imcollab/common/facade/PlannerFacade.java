package com.lark.imcollab.common.facade;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.lark.imcollab.common.domain.Conversation;
import com.lark.imcollab.common.domain.Task;

public interface PlannerFacade {
    Task plan(Conversation conversation) throws GraphRunnerException;
    Task replan(String taskId, String userFeedback) throws GraphRunnerException;
}
