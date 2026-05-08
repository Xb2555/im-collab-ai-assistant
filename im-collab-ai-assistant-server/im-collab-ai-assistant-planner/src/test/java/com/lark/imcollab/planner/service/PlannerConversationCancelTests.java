package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.supervisor.PlannerExecutionTool;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecision;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import com.lark.imcollab.planner.supervisor.PlannerToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlannerConversationCancelTests {

    @Test
    void shouldReplyToUnknownNewConversationWithoutCreatingTaskOrRunningGraph() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder().chatId("chat-1").build();
        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-chat", false, "LARK:chat-1"));
        when(intakeService.decide(any(PlanTaskSession.class), eq("哈哈哈"), eq(null), eq(false)))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.UNKNOWN,
                        "哈哈哈",
                        "small talk",
                        "我在。你把想整理的材料或目标发我就行。"
                ));

        PlanTaskSession result = service.handlePlanRequest("哈哈哈", workspaceContext, null, null);

        assertThat(result.getTaskId()).isEqualTo("task-chat");
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.INTAKE);
        assertThat(result.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.UNKNOWN);
        assertThat(result.getIntakeState().getAssistantReply()).contains("我在");
        verifyNoInteractions(sessionService, graphRunner, taskBridgeService);
    }

    @Test
    void shouldReplyToFullPlanQueryWithoutCreatingTaskOrRunningGraphWhenNoTaskExists() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder().chatId("chat-1").build();
        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-readonly", false, "LARK:chat-1"));
        when(intakeService.decide(any(PlanTaskSession.class), eq("\u5b8c\u6574\u8ba1\u5212"), eq(null), eq(false)))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.STATUS_QUERY,
                        "\u5b8c\u6574\u8ba1\u5212",
                        "read full plan without existing task",
                        "\u6211\u8fd8\u6ca1\u6709\u627e\u5230\u8fd9\u4e2a\u4f1a\u8bdd\u91cc\u7684\u4efb\u52a1\u8fdb\u5ea6\u3002\u4f60\u53ef\u4ee5\u5148\u53d1\u4e00\u4e2a\u4efb\u52a1\u7ed9\u6211\u3002"
                ));

        PlanTaskSession result = service.handlePlanRequest("\u5b8c\u6574\u8ba1\u5212", workspaceContext, null, null);

        assertThat(result.getTaskId()).isEqualTo("task-readonly");
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.INTAKE);
        assertThat(result.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.STATUS_QUERY);
        assertThat(result.getIntakeState().getAssistantReply()).contains("\u8fd8\u6ca1\u6709\u627e\u5230");
        verifyNoInteractions(sessionService, graphRunner, taskBridgeService);
    }

    @Test
    void shouldRouteCancelTaskToAbortSession() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder().chatId("chat-1").build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        PlanTaskSession abortedSession = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.ABORTED)
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.get("task-1")).thenReturn(session);
        when(intakeService.decide(session, "\u53d6\u6d88\u4efb\u52a1", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.CANCEL_TASK, "\u53d6\u6d88\u4efb\u52a1"));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"), eq("\u53d6\u6d88\u4efb\u52a1"), eq(workspaceContext), eq(null)))
                .thenReturn(abortedSession);

        PlanTaskSession result = service.handlePlanRequest("\u53d6\u6d88\u4efb\u52a1", workspaceContext, null, null);

        assertThat(result).isSameAs(abortedSession);
        verify(graphRunner).run(any(PlannerSupervisorDecision.class), eq("task-1"), eq("\u53d6\u6d88\u4efb\u52a1"), eq(workspaceContext), eq(null));
        verify(taskBridgeService).ensureTask(abortedSession);
    }

    @Test
    void shouldPassOriginalUserInputToGraphWhenIntentClassifierNormalizesText() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder().chatId("chat-1").build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-raw")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build();
        PlanTaskSession readySession = PlanTaskSession.builder()
                .taskId("task-raw")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();
        String original = "根据我没发给你的那份客户合同，整理一份风险摘要给法务看";
        String normalized = "根据客户合同整理风险摘要给法务";

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-raw", false, "LARK:chat-1"));
        when(sessionService.getOrCreate("task-raw")).thenReturn(session);
        when(intakeService.decide(any(PlanTaskSession.class), eq(original), eq(null), eq(false)))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.NEW_TASK, normalized, "llm normalized input", null));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-raw"), eq(original), eq(workspaceContext), eq(null)))
                .thenReturn(readySession);

        PlanTaskSession result = service.handlePlanRequest(original, workspaceContext, null, null);

        assertThat(result).isSameAs(readySession);
        assertThat(session.getRawInstruction()).isEqualTo(original);
        assertThat(session.getIntakeState().getLastUserMessage()).isEqualTo(original);
        verify(graphRunner).run(any(PlannerSupervisorDecision.class), eq("task-raw"), eq(original), eq(workspaceContext), eq(null));
    }

    @Test
    void askUserSessionWithDocRefsForcesClarificationReplyInsteadOfStatusQuery() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .chatId("chat-1")
                .docRefs(java.util.List.of("https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph"))
                .build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-doc")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();
        PlanTaskSession ready = PlanTaskSession.builder()
                .taskId("task-doc")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-doc", true, "LARK:chat-1"));
        when(sessionService.get("task-doc")).thenReturn(session);
        when(intakeService.decide(session, "https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph这个是文档链接", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.STATUS_QUERY,
                        "https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph这个是文档链接",
                        "hard rule read-only artifact query",
                        "现在还没有任务产物。",
                        "ARTIFACTS"
                ));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-doc"),
                eq("https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph这个是文档链接"),
                eq(workspaceContext), eq(null)))
                .thenReturn(ready);

        PlanTaskSession result = service.handlePlanRequest(
                "https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph这个是文档链接",
                workspaceContext,
                null,
                null
        );

        assertThat(result).isSameAs(ready);
        assertThat(session.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.CLARIFICATION_REPLY);
        assertThat(session.getIntakeState().getRoutingReason()).isEqualTo("guard clarification reply from extracted doc refs");
        verify(graphRunner).run(any(PlannerSupervisorDecision.class), eq("task-doc"),
                eq("https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph这个是文档链接"),
                eq(workspaceContext), eq(null));
    }

    @Test
    void standaloneNewTaskInBoundConversationGetsFreshTaskId() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder().chatId("chat-1").build();
        PlanTaskSession existing = PlanTaskSession.builder()
                .taskId("old-task")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        String input = "帮我总结群里消息并生成一个总结文档";

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("old-task", true, "LARK:chat-1"));
        when(sessionService.get("old-task")).thenReturn(existing);
        when(intakeService.decide(existing, input, null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.NEW_TASK, input, "standalone new task", null));
        when(sessionService.getOrCreate(org.mockito.ArgumentMatchers.argThat(taskId -> !"old-task".equals(taskId))))
                .thenAnswer(invocation -> PlanTaskSession.builder()
                        .taskId(invocation.getArgument(0))
                        .planningPhase(PlanningPhaseEnum.INTAKE)
                        .build());
        when(graphRunner.run(any(PlannerSupervisorDecision.class), org.mockito.ArgumentMatchers.argThat(taskId -> !"old-task".equals(taskId)), eq(input), eq(workspaceContext), eq(null)))
                .thenAnswer(invocation -> PlanTaskSession.builder()
                        .taskId(invocation.getArgument(1))
                        .planningPhase(PlanningPhaseEnum.ASK_USER)
                        .build());

        PlanTaskSession result = service.handlePlanRequest(input, workspaceContext, null, null);

        assertThat(result.getTaskId()).isNotEqualTo("old-task");
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ASK_USER);
        verify(resolver).bindConversation(org.mockito.ArgumentMatchers.argThat(resolution ->
                resolution != null && !resolution.existingSession() && !"old-task".equals(resolution.taskId())));
        verify(graphRunner).run(any(PlannerSupervisorDecision.class), eq(result.getTaskId()), eq(input), eq(workspaceContext), eq(null));
    }

    @Test
    void explicitTaskIdKeepsAsyncAcceptedTaskInsteadOfForkingNewOne() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .inputSource("GUI")
                .build();
        PlanTaskSession accepted = PlanTaskSession.builder()
                .taskId("accepted-task")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build();
        PlanTaskSession ready = PlanTaskSession.builder()
                .taskId("accepted-task")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        String input = "根据飞书项目协作方案生成一份技术方案文档，包含Mermaid架构图，再生成一份汇报PPT初稿";

        when(resolver.resolve("accepted-task", workspaceContext))
                .thenReturn(new TaskSessionResolution("accepted-task", true, null));
        when(sessionService.get("accepted-task")).thenReturn(accepted);
        when(intakeService.decide(accepted, input, null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.NEW_TASK, input, "async accepted task should continue", null));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("accepted-task"), eq(input), eq(workspaceContext), eq(null)))
                .thenReturn(ready);

        PlanTaskSession result = service.handlePlanRequest(input, workspaceContext, "accepted-task", null);

        assertThat(result.getTaskId()).isEqualTo("accepted-task");
        verify(sessionService, org.mockito.Mockito.never()).getOrCreate(org.mockito.ArgumentMatchers.argThat(taskId -> !"accepted-task".equals(taskId)));
        verify(graphRunner).run(any(PlannerSupervisorDecision.class), eq("accepted-task"), eq(input), eq(workspaceContext), eq(null));
    }

    @Test
    void executingPlanAdjustmentInterruptsReplansAndRestartsExecutionForImConversation() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlannerExecutionTool executionTool = mock(PlannerExecutionTool.class);
        TaskRuntimeService taskRuntimeService = mock(TaskRuntimeService.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner,
                executionTool,
                taskRuntimeService
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .chatId("chat-1")
                .inputSource("LARK_GROUP")
                .build();
        PlanTaskSession executing = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();
        PlanTaskSession replanned = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        PlanTaskSession replanning = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.INTERRUPTING)
                .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder().build())
                .build();
        PlanTaskSession restarted = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder().build())
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.get("task-1")).thenReturn(executing, replanning, restarted);
        when(intakeService.decide(executing, "把第三页改一下并继续跑", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, "把第三页改一下并继续跑"));
        when(executionTool.interruptExecution("task-1", "interrupt execution for plan adjustment"))
                .thenReturn(PlannerToolResult.success("task-1", PlanningPhaseEnum.INTERRUPTING, "execution interrupted", null));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"), eq("把第三页改一下并继续跑"), eq(workspaceContext), eq("把第三页改一下并继续跑")))
                .thenReturn(replanned);
        when(executionTool.confirmExecution("task-1"))
                .thenReturn(PlannerToolResult.success("task-1", PlanningPhaseEnum.EXECUTING, "execution started", null));

        PlanTaskSession result = service.handlePlanRequest("把第三页改一下并继续跑", workspaceContext, null, null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.EXECUTING);
        assertThat(result.getIntakeState().getAssistantReply()).contains("已中断当前执行", "重新开始执行");
        verify(taskRuntimeService).appendUserIntervention("task-1", "把第三页改一下并继续跑");
        verify(executionTool).interruptExecution("task-1", "interrupt execution for plan adjustment");
        verify(taskRuntimeService).projectPhaseTransition(
                "task-1",
                PlanningPhaseEnum.INTERRUPTING,
                com.lark.imcollab.common.model.enums.TaskEventTypeEnum.EXECUTION_INTERRUPTING
        );
        verify(taskRuntimeService).projectPhaseTransition(
                "task-1",
                PlanningPhaseEnum.REPLANNING,
                com.lark.imcollab.common.model.enums.TaskEventTypeEnum.PLAN_ADJUSTED
        );
        verify(graphRunner).run(any(PlannerSupervisorDecision.class), eq("task-1"), eq("把第三页改一下并继续跑"), eq(workspaceContext), eq("把第三页改一下并继续跑"));
        verify(executionTool).confirmExecution("task-1");
    }

    @Test
    void executingPlanAdjustmentStopsWhenInterruptFails() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlannerExecutionTool executionTool = mock(PlannerExecutionTool.class);
        TaskRuntimeService taskRuntimeService = mock(TaskRuntimeService.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner,
                executionTool,
                taskRuntimeService
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .chatId("chat-1")
                .inputSource("LARK_GROUP")
                .build();
        PlanTaskSession executing = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.get("task-1")).thenReturn(executing);
        when(intakeService.decide(executing, "把第三页改一下并继续跑", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, "把第三页改一下并继续跑"));
        when(executionTool.interruptExecution("task-1", "interrupt execution for plan adjustment"))
                .thenReturn(PlannerToolResult.failure("task-1", null, "执行中断失败：harness busy"));

        PlanTaskSession result = service.handlePlanRequest("把第三页改一下并继续跑", workspaceContext, null, null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.EXECUTING);
        assertThat(result.getIntakeState().getAssistantReply()).contains("当前执行还没成功中断", "harness busy");
        verify(graphRunner, never()).run(any(), any(), any(), any(), any());
        verify(executionTool, never()).confirmExecution(any());
        verify(sessionService, never()).markAborted(any(), any());
    }

    @Test
    void ambiguousExecutingPlanAdjustmentAsksForClarificationInsteadOfInterrupting() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        PlannerExecutionTool executionTool = mock(PlannerExecutionTool.class);
        TaskRuntimeService taskRuntimeService = mock(TaskRuntimeService.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner,
                executionTool,
                taskRuntimeService
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .chatId("chat-1")
                .inputSource("LARK_GROUP")
                .build();
        PlanTaskSession executing = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.get("task-1")).thenReturn(executing);
        when(intakeService.decide(executing, "标题改成 78787", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "标题改成 78787",
                        "ambiguous target",
                        null,
                        null,
                        AdjustmentTargetEnum.UNKNOWN
                ));
        when(resolver.resolveCompletedCandidates(workspaceContext))
                .thenReturn(java.util.List.of(com.lark.imcollab.common.model.entity.PendingTaskCandidate.builder()
                        .taskId("task-done")
                        .title("旧版测试文档")
                        .build()));

        PlanTaskSession result = service.handlePlanRequest("标题改成 78787", workspaceContext, null, null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.EXECUTING);
        assertThat(result.getIntakeState().getAssistantReply())
                .contains("你是要中断当前执行并重规划")
                .contains("还是修改已经生成的文档或 PPT");
        assertThat(result.getIntakeState().getAdjustmentTarget()).isEqualTo(AdjustmentTargetEnum.UNKNOWN);
        verify(graphRunner, never()).run(any(), any(), any(), any(), any());
        verify(executionTool, never()).interruptExecution(any(), any());
        verify(executionTool, never()).confirmExecution(any());
    }
}
