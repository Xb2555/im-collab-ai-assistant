package com.lark.imcollab.planner.supervisor;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.planner.service.PlannerSessionService;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlannerSupervisorGraphRunner {

    private final CompiledGraph plannerSupervisorGraph;
    private final PlannerSessionService sessionService;

    public PlannerSupervisorGraphRunner(CompiledGraph plannerSupervisorGraph, PlannerSessionService sessionService) {
        this.plannerSupervisorGraph = plannerSupervisorGraph;
        this.sessionService = sessionService;
    }

    public PlanTaskSession run(
            PlannerSupervisorDecision decision,
            String taskId,
            String rawInstruction,
            WorkspaceContext workspaceContext,
            String userFeedback
    ) {
        long startedAt = System.nanoTime();
        String resolvedTaskId = taskId == null || taskId.isBlank()
                ? UUID.randomUUID().toString()
                : taskId;
        PlannerSupervisorAction action = decision == null || decision.action() == null
                ? PlannerSupervisorAction.UNKNOWN
                : decision.action();
        Map<String, Object> input = new HashMap<>();
        input.put(PlannerSupervisorStateKeys.TASK_ID, resolvedTaskId);
        input.put(PlannerSupervisorStateKeys.ACTION, action.name());
        input.put(PlannerSupervisorStateKeys.RAW_INSTRUCTION, rawInstruction == null ? "" : rawInstruction);
        input.put(PlannerSupervisorStateKeys.USER_FEEDBACK, userFeedback == null ? "" : userFeedback);
        if (workspaceContext != null) {
            input.put(PlannerSupervisorStateKeys.WORKSPACE_CONTEXT, workspaceContext);
        }
        RunnableConfig config = RunnableConfig.builder()
                .threadId(resolvedTaskId + ":planner-supervisor:" + UUID.randomUUID())
                .build();
        try {
            PlanTaskSession session = plannerSupervisorGraph.invoke(input, config)
                    .map(this::extractSession)
                    .orElseThrow(() -> new IllegalStateException("Planner supervisor graph returned empty state"));
            printTiming("planner.graph.seconds", resolvedTaskId, startedAt, null);
            return session;
        } catch (RuntimeException exception) {
            printTiming("planner.graph.seconds", resolvedTaskId, startedAt, exception);
            throw exception;
        }
    }

    private PlanTaskSession extractSession(OverAllState state) {
        Object taskId = state.data().get(PlannerSupervisorStateKeys.TASK_ID);
        if (taskId instanceof String value && !value.isBlank()) {
            return sessionService.get(value);
        }
        throw new IllegalStateException("Planner supervisor graph did not return a task id");
    }

    private void printTiming(String metric, String taskId, long startedAt, Throwable throwable) {
        System.err.println(metric
                + " taskId=" + (taskId == null ? "" : taskId)
                + " status=" + (throwable == null ? "success" : "failed")
                + " seconds=" + String.format(java.util.Locale.ROOT, "%.3f", (System.nanoTime() - startedAt) / 1_000_000_000.0d)
                + (throwable == null ? "" : " error=" + (throwable.getMessage() == null ? "" : throwable.getMessage())));
    }
}
