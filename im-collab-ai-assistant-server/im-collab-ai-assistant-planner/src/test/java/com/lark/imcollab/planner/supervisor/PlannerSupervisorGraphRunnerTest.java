package com.lark.imcollab.planner.supervisor;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.runtime.TaskRuntimeProjectionService;
import com.lark.imcollab.planner.intent.UnknownIntentReplyService;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class PlannerSupervisorGraphRunnerTest {

    @Test
    void newTaskRoutesThroughPlannerNode() throws Exception {
        Fixture fixture = new Fixture();
        PlanTaskSession intake = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build();
        PlanTaskSession ready = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        when(fixture.sessionService.getOrCreate("task-1")).thenReturn(intake);
        when(fixture.decisionAgent.decide(any(), any(), eq("生成技术方案文档")))
                .thenReturn(PlannerSupervisorDecisionResult.of(PlannerSupervisorAction.NEW_TASK, 1.0d, "new task"));
        when(fixture.contextNodeService.check(intake, "task-1", "生成技术方案文档", null))
                .thenReturn(ContextSufficiencyResult.sufficient("生成技术方案文档", "ok"));
        when(fixture.planningNodeService.plan("task-1", "生成技术方案文档", null, "")).thenReturn(ready);
        when(fixture.sessionService.get("task-1")).thenReturn(ready);
        when(fixture.reviewGateNodeService.review("task-1")).thenReturn(PlanReviewResult.passed("ok"));
        when(fixture.reviewGateNodeService.gateAndProject(eq("task-1"), any())).thenReturn(ready);

        PlanTaskSession result = fixture.runner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.NEW_TASK, "new task"),
                "task-1",
                "生成技术方案文档",
                null,
                null
        );

        assertThat(result.getTaskId()).isEqualTo("task-1");
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.PLAN_READY);
        verify(fixture.planningNodeService).plan("task-1", "生成技术方案文档", null, "");
    }

    @Test
    void cancelRoutesThroughAbortNodeWithoutPlanning() throws Exception {
        Fixture fixture = new Fixture();
        PlanTaskSession current = PlanTaskSession.builder()
                .taskId("task-2")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        PlanTaskSession aborted = PlanTaskSession.builder()
                .taskId("task-2")
                .planningPhase(PlanningPhaseEnum.ABORTED)
                .build();
        when(fixture.sessionService.getOrCreate("task-2")).thenReturn(current);
        when(fixture.sessionService.get("task-2")).thenReturn(aborted);
        when(fixture.decisionAgent.decide(any(), any(), eq("取消任务")))
                .thenReturn(PlannerSupervisorDecisionResult.of(PlannerSupervisorAction.CANCEL_TASK, 1.0d, "cancel"));

        PlanTaskSession result = fixture.runner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.CANCEL_TASK, "cancel"),
                "task-2",
                "取消任务",
                null,
                null
        );

        assertThat(result.getTaskId()).isEqualTo("task-2");
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ABORTED);
        verify(fixture.sessionService).markAborted("task-2", "User cancelled from conversation: 取消任务");
        verify(fixture.runtimeProjectionService).projectStage(aborted, TaskEventTypeEnum.TASK_CANCELLED, "任务已取消");
        verifyNoInteractions(fixture.planningNodeService);
    }

    @Test
    void confirmNodeDoesNotExecuteWithoutExplicitUserCommand() throws Exception {
        Fixture fixture = new Fixture();
        PlanTaskSession current = PlanTaskSession.builder()
                .taskId("task-3")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.CONFIRM_ACTION)
                        .lastUserMessage("这个方案还行")
                        .build())
                .build();
        when(fixture.sessionService.getOrCreate("task-3")).thenReturn(current);
        when(fixture.sessionService.get("task-3")).thenReturn(current);
        when(fixture.decisionAgent.decide(any(), any(), eq("这个方案还行")))
                .thenReturn(PlannerSupervisorDecisionResult.of(PlannerSupervisorAction.CONFIRM_ACTION, 0.9d, "model drift"));

        PlanTaskSession result = fixture.runner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.CONFIRM_ACTION, "model drift"),
                "task-3",
                "这个方案还行",
                null,
                null
        );

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.PLAN_READY);
        assertThat(result.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.UNKNOWN);
        assertThat(result.getIntakeState().getAssistantReply()).contains("保留");
        verifyNoInteractions(fixture.executionTool);
    }

    private static class Fixture {
        private final PlannerSessionService sessionService = mock(PlannerSessionService.class);
        private final PlannerSupervisorDecisionAgent decisionAgent = mock(PlannerSupervisorDecisionAgent.class);
        private final PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        private final ContextNodeService contextNodeService = mock(ContextNodeService.class);
        private final PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        private final PlanningNodeService planningNodeService = mock(PlanningNodeService.class);
        private final ClarificationNodeService clarificationNodeService = mock(ClarificationNodeService.class);
        private final ReplanNodeService replanNodeService = mock(ReplanNodeService.class);
        private final ReviewGateNodeService reviewGateNodeService = mock(ReviewGateNodeService.class);
        private final ReadOnlyNodeService readOnlyNodeService = mock(ReadOnlyNodeService.class);
        private final PlannerExecutionTool executionTool = mock(PlannerExecutionTool.class);
        private final TaskRuntimeProjectionService runtimeProjectionService = mock(TaskRuntimeProjectionService.class);
        private final UnknownIntentReplyService unknownIntentReplyService = mock(UnknownIntentReplyService.class);
        private final PlannerSupervisorGraphRunner runner;

        private Fixture() throws Exception {
            when(unknownIntentReplyService.reply(any(), any(), any()))
                    .thenReturn("好的，我先保留这版计划。");
            PlannerSupervisorGraphNodes nodes = new PlannerSupervisorGraphNodes(
                    sessionService,
                    decisionAgent,
                    memoryService,
                    contextNodeService,
                    questionTool,
                    planningNodeService,
                    clarificationNodeService,
                    replanNodeService,
                    reviewGateNodeService,
                    readOnlyNodeService,
                    executionTool,
                    runtimeProjectionService,
                    unknownIntentReplyService
            );
            StateGraph graph = new StateGraph();
            graph.addNode("append_memory", nodes::appendMemory);
            graph.addNode("supervisor_decide", nodes::supervisorDecide);
            graph.addNode("context_check", nodes::contextCheck);
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
            CompiledGraph compiledGraph = graph.compile(CompileConfig.builder()
                    .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                    .build());
            runner = new PlannerSupervisorGraphRunner(compiledGraph, sessionService);
        }
    }
}
