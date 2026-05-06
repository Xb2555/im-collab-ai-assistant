package com.lark.imcollab.planner.supervisor;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.intent.IntentDecisionGuard;
import com.lark.imcollab.planner.intent.IntentRoutingResult;
import com.lark.imcollab.planner.intent.LlmIntentClassifier;
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
        verify(fixture.executionTool).cancelExecution("task-2", "cancel requested from planner conversation");
        verify(fixture.sessionService).markAborted("task-2", "User cancelled from conversation: 取消任务");
        verify(fixture.runtimeProjectionService).projectStage(aborted, TaskEventTypeEnum.TASK_CANCELLED, "任务已取消");
        verifyNoInteractions(fixture.planningNodeService);
    }

    @Test
    void confirmNodeExecutesWhenSupervisorRoutesConfirmAction() throws Exception {
        Fixture fixture = new Fixture();
        PlanTaskSession current = PlanTaskSession.builder()
                .taskId("task-3")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.CONFIRM_ACTION)
                        .lastUserMessage("帮我执行这个计划")
                        .build())
                .build();
        PlanTaskSession executing = PlanTaskSession.builder()
                .taskId("task-3")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();
        when(fixture.sessionService.getOrCreate("task-3")).thenReturn(current);
        when(fixture.sessionService.get("task-3")).thenReturn(current, executing);
        when(fixture.decisionAgent.decide(any(), any(), eq("帮我执行这个计划")))
                .thenReturn(PlannerSupervisorDecisionResult.of(PlannerSupervisorAction.CONFIRM_ACTION, 0.9d, "semantic confirm"));
        when(fixture.intentClassifier.classify(current, "帮我执行这个计划", true))
                .thenReturn(java.util.Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.CONFIRM_ACTION,
                        0.95d,
                        "semantic confirm",
                        "帮我执行这个计划",
                        false
                )));
        when(fixture.executionTool.confirmExecution("task-3"))
                .thenReturn(PlannerToolResult.success("task-3", PlanningPhaseEnum.EXECUTING, "execution started", null));

        PlanTaskSession result = fixture.runner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.CONFIRM_ACTION, "semantic confirm"),
                "task-3",
                "帮我执行这个计划",
                null,
                null
        );

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.EXECUTING);
        verify(fixture.executionTool).confirmExecution("task-3");
    }

    @Test
    void confirmNodeDoesNotExecuteWhenSemanticGuardRejectsExecution() throws Exception {
        Fixture fixture = new Fixture();
        PlanTaskSession current = PlanTaskSession.builder()
                .taskId("task-3b")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.CONFIRM_ACTION)
                        .lastUserMessage("这个方案还行")
                        .build())
                .build();
        when(fixture.sessionService.getOrCreate("task-3b")).thenReturn(current);
        when(fixture.sessionService.get("task-3b")).thenReturn(current);
        when(fixture.decisionAgent.decide(any(), any(), eq("这个方案还行")))
                .thenReturn(PlannerSupervisorDecisionResult.of(PlannerSupervisorAction.CONFIRM_ACTION, 0.9d, "model drift"));
        when(fixture.intentClassifier.classify(current, "这个方案还行", true))
                .thenReturn(java.util.Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.UNKNOWN,
                        0.91d,
                        "generic approval",
                        "这个方案还行",
                        true
                )));
        when(fixture.unknownIntentReplyService.reply(any(), eq("这个方案还行"), any()))
                .thenReturn("我先把当前任务停在这里等你一句话。想看细节、继续调整，或者直接让我开工，都可以直说。");

        PlanTaskSession result = fixture.runner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.CONFIRM_ACTION, "model drift"),
                "task-3b",
                "这个方案还行",
                null,
                null
        );

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.PLAN_READY);
        assertThat(result.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.UNKNOWN);
        assertThat(result.getIntakeState().getAssistantReply()).contains("我先把当前任务停在这里等你一句话");
        verifyNoInteractions(fixture.executionTool);
    }

    @Test
    void newTaskCollectsContextBeforePlanningWhenContextNodeRequestsCollection() throws Exception {
        Fixture fixture = new Fixture();
        WorkspaceContext initialContext = WorkspaceContext.builder().chatId("chat-1").build();
        WorkspaceContext collectedContext = WorkspaceContext.builder()
                .chatId("chat-1")
                .selectedMessages(java.util.List.of("项目目标：整理技术方案"))
                .build();
        PlanTaskSession intake = PlanTaskSession.builder()
                .taskId("task-4")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build();
        PlanTaskSession ready = PlanTaskSession.builder()
                .taskId("task-4")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        ContextSufficiencyResult needsCollection = ContextSufficiencyResult.collect(
                com.lark.imcollab.common.model.entity.ContextAcquisitionPlan.builder()
                        .needCollection(true)
                        .reason("needs im history")
                        .build(),
                "needs im history"
        );
        when(fixture.sessionService.getOrCreate("task-4")).thenReturn(intake);
        when(fixture.decisionAgent.decide(any(), any(), eq("整理刚才讨论")))
                .thenReturn(PlannerSupervisorDecisionResult.of(PlannerSupervisorAction.NEW_TASK, 1.0d, "new task"));
        when(fixture.contextNodeService.check(intake, "task-4", "整理刚才讨论", initialContext))
                .thenReturn(needsCollection);
        when(fixture.contextAcquisitionNodeService.collect(eq("task-4"), eq("整理刚才讨论"), eq(initialContext), any()))
                .thenReturn(new ContextCollectionOutcome(
                        ContextSufficiencyResult.sufficient("已读取聊天记录", "context collected"),
                        collectedContext
                ));
        when(fixture.planningNodeService.plan("task-4", "整理刚才讨论", collectedContext, "")).thenReturn(ready);
        when(fixture.sessionService.get("task-4")).thenReturn(ready);
        when(fixture.reviewGateNodeService.review("task-4")).thenReturn(PlanReviewResult.passed("ok"));
        when(fixture.reviewGateNodeService.gateAndProject(eq("task-4"), any())).thenReturn(ready);

        PlanTaskSession result = fixture.runner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.NEW_TASK, "new task"),
                "task-4",
                "整理刚才讨论",
                initialContext,
                null
        );

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.PLAN_READY);
        verify(fixture.contextAcquisitionNodeService).collect(eq("task-4"), eq("整理刚才讨论"), eq(initialContext), any());
        verify(fixture.planningNodeService).plan("task-4", "整理刚才讨论", collectedContext, "");
    }

    @Test
    void replanStopsWhenNodeNeedsUserSelection() throws Exception {
        Fixture fixture = new Fixture();
        PlanTaskSession current = PlanTaskSession.builder()
                .taskId("task-5")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PlanTaskSession waiting = PlanTaskSession.builder()
                .taskId("task-5")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT)
                        .assistantReply("这个任务下有多个可修改产物，你想修改哪一个？")
                        .pendingAdjustmentInstruction("把PPT标题改了")
                        .build())
                .build();
        when(fixture.sessionService.getOrCreate("task-5")).thenReturn(current);
        when(fixture.sessionService.get("task-5")).thenReturn(waiting);
        when(fixture.decisionAgent.decide(any(), any(), eq("把PPT标题改了")))
                .thenReturn(PlannerSupervisorDecisionResult.of(PlannerSupervisorAction.PLAN_ADJUSTMENT, 1.0d, "adjust"));
        when(fixture.replanNodeService.replan("task-5", "把PPT标题改了", null)).thenReturn(waiting);

        PlanTaskSession result = fixture.runner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "adjust"),
                "task-5",
                "把PPT标题改了",
                null,
                null
        );

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ASK_USER);
        assertThat(result.getIntakeState().getAssistantReply()).contains("多个可修改产物");
        verifyNoInteractions(fixture.reviewGateNodeService);
    }

    @Test
    void clarificationReplyPassesWorkspaceContextToResumeNode() throws Exception {
        Fixture fixture = new Fixture();
        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .selectedMessages(java.util.List.of("追问补充材料：采购预算100元"))
                .build();
        PlanTaskSession current = PlanTaskSession.builder()
                .taskId("task-6")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();
        PlanTaskSession ready = PlanTaskSession.builder()
                .taskId("task-6")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        when(fixture.sessionService.getOrCreate("task-6")).thenReturn(current);
        when(fixture.decisionAgent.decide(any(), any(), eq("采购预算100元")))
                .thenReturn(PlannerSupervisorDecisionResult.of(PlannerSupervisorAction.CLARIFICATION_REPLY, 1.0d, "resume"));
        when(fixture.clarificationNodeService.resume("task-6", "采购预算100元", workspaceContext)).thenReturn(ready);
        when(fixture.sessionService.get("task-6")).thenReturn(ready);
        when(fixture.reviewGateNodeService.review("task-6")).thenReturn(PlanReviewResult.passed("ok"));
        when(fixture.reviewGateNodeService.gateAndProject(eq("task-6"), any())).thenReturn(ready);

        PlanTaskSession result = fixture.runner.run(
                new PlannerSupervisorDecision(PlannerSupervisorAction.CLARIFICATION_REPLY, "resume"),
                "task-6",
                "采购预算100元",
                workspaceContext,
                "采购预算100元"
        );

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.PLAN_READY);
        verify(fixture.clarificationNodeService).resume("task-6", "采购预算100元", workspaceContext);
    }

    private static class Fixture {
        private final PlannerSessionService sessionService = mock(PlannerSessionService.class);
        private final PlannerSupervisorDecisionAgent decisionAgent = mock(PlannerSupervisorDecisionAgent.class);
        private final PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        private final ContextNodeService contextNodeService = mock(ContextNodeService.class);
        private final ContextAcquisitionNodeService contextAcquisitionNodeService = mock(ContextAcquisitionNodeService.class);
        private final PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        private final PlanningNodeService planningNodeService = mock(PlanningNodeService.class);
        private final ClarificationNodeService clarificationNodeService = mock(ClarificationNodeService.class);
        private final ReplanNodeService replanNodeService = mock(ReplanNodeService.class);
        private final ReviewGateNodeService reviewGateNodeService = mock(ReviewGateNodeService.class);
        private final ReadOnlyNodeService readOnlyNodeService = mock(ReadOnlyNodeService.class);
        private final PlannerExecutionTool executionTool = mock(PlannerExecutionTool.class);
        private final TaskRuntimeProjectionService runtimeProjectionService = mock(TaskRuntimeProjectionService.class);
        private final LlmIntentClassifier intentClassifier = mock(LlmIntentClassifier.class);
        private final IntentDecisionGuard intentDecisionGuard = new IntentDecisionGuard(new PlannerProperties());
        private final UnknownIntentReplyService unknownIntentReplyService = mock(UnknownIntentReplyService.class);
        private final PlannerSupervisorGraphRunner runner;

        private Fixture() throws Exception {
            PlannerSupervisorGraphNodes nodes = new PlannerSupervisorGraphNodes(
                    sessionService,
                    decisionAgent,
                    memoryService,
                    contextNodeService,
                    contextAcquisitionNodeService,
                    questionTool,
                    planningNodeService,
                    clarificationNodeService,
                    replanNodeService,
                    reviewGateNodeService,
                    readOnlyNodeService,
                    executionTool,
                    runtimeProjectionService,
                    intentClassifier,
                    intentDecisionGuard,
                    unknownIntentReplyService
            );
            StateGraph graph = new StateGraph();
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
            graph.addConditionalEdges("replan", nodes::routeAfterReplan, Map.of(
                    "REVIEW", "review",
                    "END", StateGraph.END
            ));
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
