package com.lark.imcollab.planner.facade;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.lark.imcollab.common.domain.Conversation;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.facade.PlannerFacade;
import com.lark.imcollab.planner.plan.PlanGate;
import com.lark.imcollab.planner.plan.Replanner;
import com.lark.imcollab.planner.plan.TaskPlanner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultPlannerFacade implements PlannerFacade {

    private final TaskPlanner taskPlanner;
    private final Replanner replanner;
    private final PlanGate planGate;

    @Override
    public Task plan(Conversation conversation) throws GraphRunnerException {
        Task task = taskPlanner.plan(conversation);
        if (!planGate.pass(task)) {
            throw new IllegalStateException("Plan gate failed for task: " + task.getTaskId());
        }
        return task;
    }

    @Override
    public Task replan(String taskId, String userFeedback) throws GraphRunnerException {
        Task task = replanner.replan(taskId, userFeedback);
        if (!planGate.pass(task)) {
            throw new IllegalStateException("Plan gate failed on replan for task: " + taskId);
        }
        return task;
    }
}
