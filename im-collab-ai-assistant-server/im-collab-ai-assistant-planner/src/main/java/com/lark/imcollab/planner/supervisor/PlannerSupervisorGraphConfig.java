package com.lark.imcollab.planner.supervisor;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.store.checkpoint.CheckpointSaverProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class PlannerSupervisorGraphConfig {

    @Bean(name = "plannerSupervisorGraph")
    public CompiledGraph plannerSupervisorGraph(
            PlannerSupervisorGraphNodes nodes,
            CheckpointSaverProvider checkpointSaverProvider
    ) throws Exception {
        StateGraph graph = new StateGraph(
                "planner-supervisor-graph",
                new KeyStrategyFactoryBuilder()
                        .defaultStrategy(KeyStrategy.REPLACE)
                        .build()
        );
        graph.addNode("append_memory", nodes::appendMemory);
        graph.addNode("supervisor_decide", nodes::supervisorDecide);
        graph.addNode("context_check", nodes::contextCheck);
        graph.addNode("collect_context", nodes::collectContext);
        graph.addNode("clarify", nodes::clarify);
        graph.addNode("plan", nodes::plan);
        graph.addNode("resume", nodes::resume);
        graph.addNode("replan", nodes::replan);
        graph.addNode("review", nodes::review);
        graph.addNode("gate", nodes::gate);
        graph.addNode("project_runtime", nodes::projectRuntime);
        graph.addNode("confirm", nodes::confirm);
        graph.addNode("cancel", nodes::cancel);
        graph.addNode("read_only", nodes::readOnly);

        graph.addEdge(StateGraph.START, "append_memory");
        graph.addEdge("append_memory", "supervisor_decide");
        graph.addConditionalEdges("supervisor_decide", nodes::route, Map.of(
                PlannerSupervisorAction.NEW_TASK.name(), "context_check",
                PlannerSupervisorAction.CLARIFICATION_REPLY.name(), "resume",
                PlannerSupervisorAction.PLAN_ADJUSTMENT.name(), "replan",
                PlannerSupervisorAction.CANCEL_TASK.name(), "cancel",
                PlannerSupervisorAction.QUERY_STATUS.name(), "read_only",
                PlannerSupervisorAction.CONFIRM_ACTION.name(), "confirm",
                PlannerSupervisorAction.UNKNOWN.name(), "read_only"
        ));
        graph.addConditionalEdges("context_check", nodes::routeContext, Map.of(
                "CLARIFY", "clarify",
                "COLLECT", "collect_context",
                "PLAN", "plan"
        ));
        graph.addConditionalEdges("collect_context", nodes::routeContext, Map.of(
                "CLARIFY", "clarify",
                "COLLECT", "clarify",
                "PLAN", "plan"
        ));
        graph.addEdge("plan", "review");
        graph.addEdge("resume", "review");
        graph.addEdge("replan", "review");
        graph.addEdge("review", "gate");
        graph.addEdge("gate", "project_runtime");
        graph.addEdge("project_runtime", StateGraph.END);
        graph.addEdge("clarify", StateGraph.END);
        graph.addEdge("confirm", StateGraph.END);
        graph.addEdge("cancel", StateGraph.END);
        graph.addEdge("read_only", StateGraph.END);

        return graph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder()
                        .register(checkpointSaverProvider.getCheckpointSaver())
                        .build())
                .build());
    }

    @Bean
    public PlannerSupervisorGraphRunner plannerSupervisorGraphRunner(
            @Qualifier("plannerSupervisorGraph") CompiledGraph plannerSupervisorGraph,
            PlannerSessionService sessionService
    ) {
        return new PlannerSupervisorGraphRunner(plannerSupervisorGraph, sessionService);
    }
}
