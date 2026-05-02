package com.lark.imcollab.planner.supervisor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.util.ExecutionCommandGuard;
import com.lark.imcollab.planner.intent.UnknownIntentReplyService;
import com.lark.imcollab.planner.runtime.TaskRuntimeProjectionService;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class PlannerSupervisorGraphNodes {

    private final PlannerSessionService sessionService;
    private final PlannerSupervisorDecisionAgent decisionAgent;
    private final PlannerConversationMemoryService memoryService;
    private final ContextNodeService contextNodeService;
    private final PlannerQuestionTool questionTool;
    private final PlanningNodeService planningNodeService;
    private final ClarificationNodeService clarificationNodeService;
    private final ReplanNodeService replanNodeService;
    private final ReviewGateNodeService reviewGateNodeService;
    private final ReadOnlyNodeService readOnlyNodeService;
    private final PlannerExecutionTool executionTool;
    private final TaskRuntimeProjectionService runtimeProjectionService;
    private final UnknownIntentReplyService unknownIntentReplyService;

    public PlannerSupervisorGraphNodes(
            PlannerSessionService sessionService,
            PlannerSupervisorDecisionAgent decisionAgent,
            PlannerConversationMemoryService memoryService,
            ContextNodeService contextNodeService,
            PlannerQuestionTool questionTool,
            PlanningNodeService planningNodeService,
            ClarificationNodeService clarificationNodeService,
            ReplanNodeService replanNodeService,
            ReviewGateNodeService reviewGateNodeService,
            ReadOnlyNodeService readOnlyNodeService,
            PlannerExecutionTool executionTool,
            TaskRuntimeProjectionService runtimeProjectionService,
            UnknownIntentReplyService unknownIntentReplyService
    ) {
        this.sessionService = sessionService;
        this.decisionAgent = decisionAgent;
        this.memoryService = memoryService;
        this.contextNodeService = contextNodeService;
        this.questionTool = questionTool;
        this.planningNodeService = planningNodeService;
        this.clarificationNodeService = clarificationNodeService;
        this.replanNodeService = replanNodeService;
        this.reviewGateNodeService = reviewGateNodeService;
        this.readOnlyNodeService = readOnlyNodeService;
        this.executionTool = executionTool;
        this.runtimeProjectionService = runtimeProjectionService;
        this.unknownIntentReplyService = unknownIntentReplyService;
    }

    public CompletableFuture<Map<String, Object>> appendMemory(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PlannerSupervisorStateKeys.TASK_ID, "");
        PlanTaskSession session = sessionService.getOrCreate(taskId);
        String rawInstruction = state.value(PlannerSupervisorStateKeys.RAW_INSTRUCTION, "");
        if (rawInstruction != null && !rawInstruction.isBlank()) {
            memoryService.appendUserTurnIfLatestDifferent(session, rawInstruction, null, "PLANNER_GRAPH");
            sessionService.saveWithoutVersionChange(session);
        }
        return CompletableFuture.completedFuture(Map.of(
                PlannerSupervisorStateKeys.RESULT_PHASE, session.getPlanningPhase() == null ? "" : session.getPlanningPhase().name(),
                PlannerSupervisorStateKeys.MESSAGE, "memory appended"
        ));
    }

    public CompletableFuture<Map<String, Object>> supervisorDecide(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PlannerSupervisorStateKeys.TASK_ID, "");
        PlanTaskSession session = sessionService.getOrCreate(taskId);
        String rawInstruction = state.value(PlannerSupervisorStateKeys.RAW_INSTRUCTION, "");
        PlannerSupervisorDecision intakeDecision = new PlannerSupervisorDecision(
                parseAction(state.value(PlannerSupervisorStateKeys.ACTION, PlannerSupervisorAction.UNKNOWN.name())),
                state.value(PlannerSupervisorStateKeys.MESSAGE, "")
        );
        PlannerSupervisorDecisionResult decision = decisionAgent.decide(session, intakeDecision, rawInstruction);
        return CompletableFuture.completedFuture(Map.of(
                PlannerSupervisorStateKeys.ACTION, decision.action().name(),
                PlannerSupervisorStateKeys.DECISION_RESULT, decision,
                PlannerSupervisorStateKeys.RESULT_PHASE, session.getPlanningPhase() == null ? "" : session.getPlanningPhase().name(),
                PlannerSupervisorStateKeys.MESSAGE, decision.reason() == null ? "" : decision.reason()
        ));
    }

    public CompletableFuture<Map<String, Object>> contextCheck(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PlannerSupervisorStateKeys.TASK_ID, "");
        PlanTaskSession session = sessionService.getOrCreate(taskId);
        ContextSufficiencyResult result = contextNodeService.check(
                session,
                taskId,
                state.value(PlannerSupervisorStateKeys.RAW_INSTRUCTION, ""),
                workspaceContext(state)
        );
        return CompletableFuture.completedFuture(Map.of(
                PlannerSupervisorStateKeys.CONTEXT_RESULT, result,
                PlannerSupervisorStateKeys.RESULT_PHASE, session.getPlanningPhase() == null ? "" : session.getPlanningPhase().name(),
                PlannerSupervisorStateKeys.MESSAGE, result.reason() == null ? "" : result.reason()
        ));
    }

    public CompletableFuture<Map<String, Object>> clarify(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PlannerSupervisorStateKeys.TASK_ID, "");
        PlanTaskSession session = sessionService.getOrCreate(taskId);
        PlannerSupervisorDecisionResult decision = decisionResult(state);
        ContextSufficiencyResult contextResult = state.value(PlannerSupervisorStateKeys.CONTEXT_RESULT, ContextSufficiencyResult.class)
                .orElse(null);
        String question = firstNonBlank(
                decision == null ? null : decision.clarificationQuestion(),
                contextResult == null ? null : contextResult.clarificationQuestion(),
                "我还需要确认一下：你希望我基于哪些内容，输出文档、PPT 还是摘要？"
        );
        PlannerToolResult result = questionTool.askUser(session, List.of(question));
        return completed(sessionService.get(taskId), result.message());
    }

    public CompletableFuture<Map<String, Object>> plan(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PlannerSupervisorStateKeys.TASK_ID, "");
        String rawInstruction = state.value(PlannerSupervisorStateKeys.RAW_INSTRUCTION, "");
        String userFeedback = state.value(PlannerSupervisorStateKeys.USER_FEEDBACK, "");
        WorkspaceContext workspaceContext = workspaceContext(state);
        return completed(planningNodeService.plan(taskId, rawInstruction, workspaceContext, userFeedback), "plan completed");
    }

    public CompletableFuture<Map<String, Object>> resume(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PlannerSupervisorStateKeys.TASK_ID, "");
        String rawInstruction = state.value(PlannerSupervisorStateKeys.RAW_INSTRUCTION, "");
        return completed(clarificationNodeService.resume(taskId, rawInstruction), "resume completed");
    }

    public CompletableFuture<Map<String, Object>> replan(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PlannerSupervisorStateKeys.TASK_ID, "");
        String rawInstruction = state.value(PlannerSupervisorStateKeys.RAW_INSTRUCTION, "");
        WorkspaceContext workspaceContext = workspaceContext(state);
        return completed(replanNodeService.replan(taskId, rawInstruction, workspaceContext), "replan completed");
    }

    public CompletableFuture<Map<String, Object>> review(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PlannerSupervisorStateKeys.TASK_ID, "");
        PlanTaskSession session = sessionService.get(taskId);
        PlanReviewResult result = reviewGateNodeService.review(taskId);
        if (result == null) {
            result = PlanReviewResult.passed("review skipped");
        }
        PlanTaskSession reviewed = sessionService.get(taskId);
        return CompletableFuture.completedFuture(Map.of(
                PlannerSupervisorStateKeys.REVIEW_RESULT, result,
                PlannerSupervisorStateKeys.RESULT_PHASE, reviewed == null || reviewed.getPlanningPhase() == null ? "" : reviewed.getPlanningPhase().name(),
                PlannerSupervisorStateKeys.MESSAGE, result.message() == null ? "review passed" : result.message()
        ));
    }

    public CompletableFuture<Map<String, Object>> gate(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PlannerSupervisorStateKeys.TASK_ID, "");
        TaskEventTypeEnum readyEvent = PlannerSupervisorAction.PLAN_ADJUSTMENT.name()
                .equals(state.value(PlannerSupervisorStateKeys.ACTION, PlannerSupervisorAction.UNKNOWN.name()))
                ? TaskEventTypeEnum.PLAN_ADJUSTED
                : TaskEventTypeEnum.PLAN_READY;
        return completed(reviewGateNodeService.gateAndProject(taskId, readyEvent), "gate completed");
    }

    public CompletableFuture<Map<String, Object>> projectRuntime(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PlannerSupervisorStateKeys.TASK_ID, "");
        return completed(sessionService.get(taskId), "runtime projected by planner flow");
    }

    public CompletableFuture<Map<String, Object>> confirm(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PlannerSupervisorStateKeys.TASK_ID, "");
        String rawInstruction = state.value(PlannerSupervisorStateKeys.RAW_INSTRUCTION, "");
        if (!ExecutionCommandGuard.isExplicitExecutionRequest(rawInstruction)) {
            PlanTaskSession session = sessionService.get(taskId);
            if (session == null) {
                return completed(null, "confirm ignored because session missing");
            }
            TaskIntakeState intakeState = session.getIntakeState();
            if (intakeState == null) {
                intakeState = TaskIntakeState.builder().build();
                session.setIntakeState(intakeState);
            }
            intakeState.setIntakeType(TaskIntakeTypeEnum.UNKNOWN);
            String reply = unknownIntentReplyService == null
                    ? "我先不动当前计划。想看细节、调整步骤或推进执行，都可以直接说。"
                    : unknownIntentReplyService.reply(session, rawInstruction, "not an explicit execution request");
            intakeState.setAssistantReply(reply);
            memoryService.appendAssistantTurn(session, reply);
            sessionService.saveWithoutVersionChange(session);
            return completed(session, "confirm ignored because user did not explicitly request execution");
        }
        PlannerToolResult result = executionTool.confirmExecution(taskId);
        return completed(sessionService.get(taskId), result.message());
    }

    public CompletableFuture<Map<String, Object>> cancel(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PlannerSupervisorStateKeys.TASK_ID, "");
        String rawInstruction = state.value(PlannerSupervisorStateKeys.RAW_INSTRUCTION, "");
        sessionService.markAborted(taskId, "User cancelled from conversation: " + rawInstruction);
        PlanTaskSession cancelled = sessionService.get(taskId);
        memoryService.appendAssistantTurn(cancelled, "任务已取消");
        sessionService.saveWithoutVersionChange(cancelled);
        runtimeProjectionService.projectStage(cancelled, TaskEventTypeEnum.TASK_CANCELLED, "任务已取消");
        return completed(cancelled, "cancel completed");
    }

    public CompletableFuture<Map<String, Object>> readOnly(OverAllState state, RunnableConfig config) {
        String taskId = state.value(PlannerSupervisorStateKeys.TASK_ID, "");
        PlannerSupervisorDecisionResult decision = decisionResult(state);
        return completed(readOnlyNodeService.readOnly(
                taskId,
                state.value(PlannerSupervisorStateKeys.RAW_INSTRUCTION, ""),
                decision
        ), "read-only action");
    }

    public CompletableFuture<Map<String, Object>> routeNode(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(Map.of(
                PlannerSupervisorStateKeys.MESSAGE,
                "route=" + state.value(PlannerSupervisorStateKeys.ACTION, PlannerSupervisorAction.UNKNOWN.name())
        ));
    }

    public CompletableFuture<String> route(OverAllState state, RunnableConfig config) {
        String action = state.value(PlannerSupervisorStateKeys.ACTION, PlannerSupervisorAction.UNKNOWN.name());
        return CompletableFuture.completedFuture(action);
    }

    public CompletableFuture<String> routeContext(OverAllState state, RunnableConfig config) {
        ContextSufficiencyResult result = state.value(PlannerSupervisorStateKeys.CONTEXT_RESULT, ContextSufficiencyResult.class)
                .orElse(null);
        if (result != null && !result.sufficient()) {
            return CompletableFuture.completedFuture("CLARIFY");
        }
        return CompletableFuture.completedFuture("PLAN");
    }

    private WorkspaceContext workspaceContext(OverAllState state) {
        return state.value(PlannerSupervisorStateKeys.WORKSPACE_CONTEXT, WorkspaceContext.class).orElse(null);
    }

    private PlannerSupervisorDecisionResult decisionResult(OverAllState state) {
        return state.value(PlannerSupervisorStateKeys.DECISION_RESULT, PlannerSupervisorDecisionResult.class).orElse(null);
    }

    private PlannerSupervisorAction parseAction(String value) {
        try {
            return PlannerSupervisorAction.valueOf(value == null ? PlannerSupervisorAction.UNKNOWN.name() : value);
        } catch (IllegalArgumentException ignored) {
            return PlannerSupervisorAction.UNKNOWN;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private CompletableFuture<Map<String, Object>> completed(PlanTaskSession session, String message) {
        return CompletableFuture.completedFuture(Map.of(
                PlannerSupervisorStateKeys.RESULT_PHASE, session.getPlanningPhase() == null ? "" : session.getPlanningPhase().name(),
                PlannerSupervisorStateKeys.MESSAGE, message
        ));
    }
}
