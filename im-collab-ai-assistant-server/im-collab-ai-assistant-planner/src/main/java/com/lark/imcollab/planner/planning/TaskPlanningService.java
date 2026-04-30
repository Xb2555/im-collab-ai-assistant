package com.lark.imcollab.planner.planning;

import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.planner.service.ExecutionContractFactory;
import com.lark.imcollab.planner.service.PlanGraphBuilder;
import org.springframework.stereotype.Service;

@Service
public class TaskPlanningService {

    private final PlanGraphBuilder planGraphBuilder;
    private final ExecutionContractFactory executionContractFactory;

    public TaskPlanningService(PlanGraphBuilder planGraphBuilder, ExecutionContractFactory executionContractFactory) {
        this.planGraphBuilder = planGraphBuilder;
        this.executionContractFactory = executionContractFactory;
    }

    public TaskPlanningResult buildReadyPlan(PlanTaskSession session) {
        PlanBlueprint blueprint = session == null ? null : session.getPlanBlueprint();
        TaskPlanGraph graph = planGraphBuilder.build(session == null ? null : session.getTaskId(), blueprint);
        ExecutionContract contract = executionContractFactory.build(session);
        return new TaskPlanningResult(blueprint, graph, contract);
    }
}
