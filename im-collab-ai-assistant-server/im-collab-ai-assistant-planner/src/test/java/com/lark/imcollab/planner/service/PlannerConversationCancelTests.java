package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.common.model.enums.PendingInteractionTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.exception.VersionConflictException;
import com.lark.imcollab.planner.supervisor.PlannerExecutionTool;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecision;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import com.lark.imcollab.planner.supervisor.PlannerToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.doAnswer;
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
    void shouldRejectExecutionConfirmationBeforePlanReady() {
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
        PlanTaskSession intake = PlanTaskSession.builder()
                .taskId("task-intake")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build();
        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-intake", true, "LARK:chat-1"));
        when(sessionService.get("task-intake")).thenReturn(intake);
        when(intakeService.decide(intake, "开始执行", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.CONFIRM_ACTION, "开始执行", "explicit confirm", null));

        PlanTaskSession result = service.handlePlanRequest("开始执行", workspaceContext, null, null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.INTAKE);
        assertThat(result.getIntakeState().getAssistantReply()).contains("当前还没到可执行阶段");
        verify(graphRunner, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void shouldTreatReadyPlanSourceSupplementAsClarificationResume() {
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
        PlanTaskSession ready = PlanTaskSession.builder()
                .taskId("task-ready")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-ready", true, "LARK:chat-1"));
        when(sessionService.get("task-ready")).thenReturn(ready);
        when(intakeService.decide(ready, "拉取前10分钟的消息作为文档内容来源，直接生成即可。", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "拉取前10分钟的消息作为文档内容来源，直接生成即可。",
                        "model source supplement",
                        null
                ));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-ready"),
                eq("拉取前10分钟的消息作为文档内容来源，直接生成即可。"), eq(workspaceContext), eq(null)))
                .thenAnswer(invocation -> ready);

        service.handlePlanRequest("拉取前10分钟的消息作为文档内容来源，直接生成即可。", workspaceContext, null, null);

        verify(graphRunner).run(
                eq(new PlannerSupervisorDecision(com.lark.imcollab.planner.supervisor.PlannerSupervisorAction.CLARIFICATION_REPLY,
                        "guard source context supplement for ready plan")),
                eq("task-ready"),
                eq("拉取前10分钟的消息作为文档内容来源，直接生成即可。"),
                eq(workspaceContext),
                eq(null)
        );
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
    void completedDocFollowUpCarriesPreviousDocUrlIntoFreshTaskWorkspaceContext() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        FollowUpArtifactContextResolver followUpResolver = mock(FollowUpArtifactContextResolver.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner,
                null,
                null,
                followUpResolver,
                null,
                null
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .chatId("chat-1")
                .threadId("thread-1")
                .inputSource("LARK_GROUP")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("old-task")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        String input = "再帮我基于这个文档生成一版汇报ppt";

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("old-task", true, "LARK:chat-1"));
        when(sessionService.get("old-task")).thenReturn(completed);
        when(intakeService.decide(completed, input, null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.NEW_TASK, input, "standalone new task", null));
        when(followUpResolver.resolvePreferredArtifactType(completed, input, workspaceContext))
                .thenReturn(Optional.of(ArtifactTypeEnum.DOC));
        when(resolver.findLatestShareableArtifact("old-task", ArtifactTypeEnum.DOC))
                .thenReturn(Optional.of(ArtifactRecord.builder()
                        .artifactId("doc-1")
                        .taskId("old-task")
                        .type(ArtifactTypeEnum.DOC)
                        .url("https://doc.example/old-task")
                        .build()));
        when(sessionService.getOrCreate(org.mockito.ArgumentMatchers.argThat(taskId -> !"old-task".equals(taskId))))
                .thenAnswer(invocation -> PlanTaskSession.builder()
                        .taskId(invocation.getArgument(0))
                        .planningPhase(PlanningPhaseEnum.INTAKE)
                        .build());
        when(graphRunner.run(
                any(PlannerSupervisorDecision.class),
                org.mockito.ArgumentMatchers.argThat(taskId -> !"old-task".equals(taskId)),
                eq(input),
                org.mockito.ArgumentMatchers.argThat(context -> context != null
                        && context.getDocRefs() != null
                        && context.getDocRefs().contains("https://doc.example/old-task")),
                eq(null)))
                .thenAnswer(invocation -> PlanTaskSession.builder()
                        .taskId(invocation.getArgument(1))
                        .planningPhase(PlanningPhaseEnum.ASK_USER)
                        .build());

        PlanTaskSession result = service.handlePlanRequest(input, workspaceContext, null, null);

        assertThat(result.getTaskId()).isNotEqualTo("old-task");
        verify(graphRunner).run(
                any(PlannerSupervisorDecision.class),
                eq(result.getTaskId()),
                eq(input),
                org.mockito.ArgumentMatchers.argThat(context -> context != null
                        && context.getDocRefs() != null
                        && context.getDocRefs().contains("https://doc.example/old-task")),
                eq(null));
    }

    @Test
    void matchedPendingFollowUpRecommendationContinuesCompletedTaskInsteadOfStartingNewTask() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        FollowUpArtifactContextResolver followUpResolver = mock(FollowUpArtifactContextResolver.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        TaskRuntimeService taskRuntimeService = mock(TaskRuntimeService.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner,
                null,
                taskRuntimeService,
                followUpResolver,
                conversationTaskStateService,
                matcher
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .chatId("chat-1")
                .threadId("thread-1")
                .inputSource("LARK_GROUP")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.STATUS_QUERY)
                        .build())
                .build();
        PendingFollowUpRecommendation recommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_PPT_FROM_DOC")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .sourceArtifactId("artifact-doc")
                .sourceArtifactType(ArtifactTypeEnum.DOC)
                .targetDeliverable(ArtifactTypeEnum.PPT)
                .plannerInstruction("保留现有文档，基于该文档新增一份汇报PPT初稿。")
                .artifactPolicy("KEEP_EXISTING_CREATE_NEW")
                .suggestedUserInstruction("基于这份文档生成一版汇报PPT")
                .priority(1)
                .build();
        ConversationTaskState conversationState = ConversationTaskState.builder()
                .conversationKey("LARK:chat-1")
                .activeTaskId("task-1")
                .lastCompletedTaskId("task-1")
                .pendingFollowUpRecommendations(List.of(recommendation))
                .build();
        ArtifactRecord artifact = ArtifactRecord.builder()
                .artifactId("artifact-doc")
                .taskId("task-1")
                .type(ArtifactTypeEnum.DOC)
                .title("项目执行方案")
                .url("https://doc.example/task-1")
                .build();
        PlanTaskSession replanned = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.STATUS_QUERY)
                        .build())
                .build();
        String input = "再帮我基于这个文档生成一版汇报用ppt，要求要5页，每页50字左右即可";

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(conversationTaskStateService.find("LARK:chat-1")).thenReturn(Optional.of(conversationState));
        when(matcher.match(input, List.of(recommendation), false))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.selected(recommendation));
        when(resolver.findArtifactById("task-1", "artifact-doc")).thenReturn(Optional.of(artifact));
        when(graphRunner.run(
                any(PlannerSupervisorDecision.class),
                eq("task-1"),
                org.mockito.ArgumentMatchers.contains("保留现有文档"),
                org.mockito.ArgumentMatchers.argThat(context -> context != null
                        && context.getSourceArtifacts() != null
                        && context.getSourceArtifacts().stream().anyMatch(ref -> "artifact-doc".equals(ref.getArtifactId()))),
                eq(input)))
                .thenReturn(replanned);

        PlanTaskSession result = service.handlePlanRequest(input, workspaceContext, null, null);

        assertThat(result).isSameAs(replanned);
        assertThat(result.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        assertThat(result.getIntakeState().getRoutingReason()).isEqualTo("resume pending follow-up recommendation");
        verify(graphRunner).run(
                any(PlannerSupervisorDecision.class),
                eq("task-1"),
                org.mockito.ArgumentMatchers.contains("产物策略：KEEP_EXISTING_CREATE_NEW"),
                org.mockito.ArgumentMatchers.argThat(context -> context != null
                        && context.getSourceArtifacts() != null
                        && context.getSourceArtifacts().stream().anyMatch(ref -> "artifact-doc".equals(ref.getArtifactId()))),
                eq(input));
        verify(conversationTaskStateService).clearPendingFollowUpRecommendations("LARK:chat-1");
        verify(taskBridgeService).ensureTask(replanned);
        verify(taskRuntimeService).reconcilePlanReadyProjection(
                eq(replanned),
                eq(com.lark.imcollab.common.model.enums.TaskEventTypeEnum.PLAN_ADJUSTED)
        );
        verify(intakeService, never()).decide(any(), any(), any(), anyBoolean());
    }

    @Test
    void explicitExecutionOnPlanReadyTaskDoesNotResumePendingFollowUpRecommendation() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner,
                null,
                null,
                null,
                conversationTaskStateService,
                matcher
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .chatId("chat-1")
                .threadId("thread-1")
                .inputSource("LARK_GROUP")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession ready = PlanTaskSession.builder()
                .taskId("task-ready")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        PlanTaskSession executing = PlanTaskSession.builder()
                .taskId("task-ready")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();
        PendingFollowUpRecommendation recommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("rec-ppt")
                .targetTaskId("completed-task")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .sourceArtifactId("artifact-doc")
                .sourceArtifactType(ArtifactTypeEnum.DOC)
                .targetDeliverable(ArtifactTypeEnum.PPT)
                .plannerInstruction("保留现有文档，基于该文档新增一份汇报PPT初稿。")
                .artifactPolicy("KEEP_EXISTING_CREATE_NEW")
                .suggestedUserInstruction("基于这份文档生成一版汇报PPT")
                .priority(1)
                .build();
        ConversationTaskState state = ConversationTaskState.builder()
                .conversationKey("LARK:chat-1")
                .activeTaskId("task-ready")
                .lastCompletedTaskId("completed-task")
                .pendingFollowUpRecommendations(List.of(recommendation))
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-ready", true, "LARK:chat-1"));
        when(sessionService.get("task-ready")).thenReturn(ready);
        when(conversationTaskStateService.find("LARK:chat-1")).thenReturn(Optional.of(state));
        when(intakeService.decide(ready, "开始执行", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.CONFIRM_ACTION, "开始执行", "explicit confirm", null));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-ready"), eq("开始执行"), eq(workspaceContext), eq(null)))
                .thenReturn(executing);

        PlanTaskSession result = service.handlePlanRequest("开始执行", workspaceContext, null, null);

        assertThat(result).isSameAs(executing);
        verify(graphRunner).run(any(PlannerSupervisorDecision.class), eq("task-ready"), eq("开始执行"), eq(workspaceContext), eq(null));
        verify(matcher, never()).match(any(), any(), anyBoolean());
    }

    @Test
    void confirmActionKeepsPreserveExistingArtifactsFlagForFollowUpContinuation() {
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
                .threadId("thread-1")
                .inputSource("LARK_PRIVATE_CHAT")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession ready = PlanTaskSession.builder()
                .taskId("task-ready")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder()
                        .preserveExistingArtifactsOnExecution(true)
                        .continuationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .build())
                .build();
        PlanTaskSession executing = PlanTaskSession.builder()
                .taskId("task-ready")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder().build())
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-ready", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-ready")).thenReturn(ready);
        when(intakeService.decide(ready, "开始执行", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.CONFIRM_ACTION, "开始执行", "explicit confirm", null));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-ready"), eq("开始执行"), eq(workspaceContext), eq(null)))
                .thenReturn(executing);

        PlanTaskSession result = service.handlePlanRequest("开始执行", workspaceContext, null, null);

        assertThat(result).isSameAs(executing);
        assertThat(ready.getIntakeState().isPreserveExistingArtifactsOnExecution()).isTrue();
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
    void executingPlanAdjustmentInterruptsAndReturnsPlanReadyForImConversation() {
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
        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.get("task-1")).thenReturn(executing, replanning);
        when(intakeService.decide(executing, "把第三页改一下并继续跑", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, "把第三页改一下并继续跑"));
        when(executionTool.interruptExecution("task-1", "interrupt execution for plan adjustment"))
                .thenReturn(PlannerToolResult.success("task-1", PlanningPhaseEnum.INTERRUPTING, "execution interrupted", null));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"), eq("把第三页改一下并继续跑"), eq(workspaceContext), eq("把第三页改一下并继续跑")))
                .thenReturn(replanned);

        PlanTaskSession result = service.handlePlanRequest("把第三页改一下并继续跑", workspaceContext, null, null);

        assertThat(result).isSameAs(replanned);
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.PLAN_READY);
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
        verify(taskRuntimeService).reconcilePlanReadyProjection(
                eq(replanned),
                eq(com.lark.imcollab.common.model.enums.TaskEventTypeEnum.PLAN_ADJUSTED)
        );
        verify(executionTool, never()).confirmExecution(any());
    }

    @Test
    void executingPlanAdjustmentRetriesAfterSessionStateConflictDuringInterruptFlow() {
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
        PlanTaskSession refreshed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();
        PlanTaskSession replanning = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder().build())
                .build();
        PlanTaskSession replanned = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.get("task-1")).thenReturn(executing, refreshed, replanning);
        when(intakeService.decide(executing, "帮我补一节关于 ggbond 的内容", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "帮我补一节关于 ggbond 的内容",
                        "adjust current running plan",
                        null,
                        null,
                        AdjustmentTargetEnum.RUNNING_PLAN
                ));
        AtomicInteger saveAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (saveAttempts.getAndIncrement() == 0) {
                throw new VersionConflictException("Session state conflict: taskId=task-1, expectedStateRevision=1, actualStateRevision=2");
            }
            return invocation.getArgument(0);
        }).when(sessionService).saveWithoutVersionChange(any(PlanTaskSession.class));
        when(executionTool.interruptExecution("task-1", "interrupt execution for plan adjustment"))
                .thenReturn(PlannerToolResult.success("task-1", PlanningPhaseEnum.INTERRUPTING, "execution interrupted", null));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"),
                eq("帮我补一节关于 ggbond 的内容"), eq(workspaceContext), eq("帮我补一节关于 ggbond 的内容")))
                .thenReturn(replanned);

        PlanTaskSession result = service.handlePlanRequest("帮我补一节关于 ggbond 的内容", workspaceContext, null, null);

        assertThat(result).isSameAs(replanned);
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.PLAN_READY);
        verify(graphRunner).run(any(PlannerSupervisorDecision.class), eq("task-1"),
                eq("帮我补一节关于 ggbond 的内容"), eq(workspaceContext), eq("帮我补一节关于 ggbond 的内容"));
        verify(executionTool, never()).confirmExecution(any());
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
        assertThat(result.getIntakeState().getAssistantReply()).contains("当前执行还没成功中断", "请稍后再试");
        verify(graphRunner, never()).run(any(), any(), any(), any(), any());
        verify(executionTool, never()).confirmExecution(any());
        verify(sessionService, never()).markAborted(any(), any());
    }

    @Test
    void ambiguousExecutingPlanAdjustmentInterruptsAndReplansCurrentTask() {
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
        PlanTaskSession replanning = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.INTERRUPTING)
                .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder().build())
                .build();
        PlanTaskSession replanned = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        when(sessionService.get("task-1")).thenReturn(executing, replanning);
        when(executionTool.interruptExecution("task-1", "interrupt execution for plan adjustment"))
                .thenReturn(PlannerToolResult.success("task-1", PlanningPhaseEnum.INTERRUPTING, "execution interrupted", null));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"), eq("标题改成 78787"), eq(workspaceContext), eq("标题改成 78787")))
                .thenReturn(replanned);

        PlanTaskSession result = service.handlePlanRequest("标题改成 78787", workspaceContext, null, null);

        assertThat(result).isSameAs(replanned);
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.PLAN_READY);
        verify(graphRunner).run(any(), eq("task-1"), eq("标题改成 78787"), eq(workspaceContext), eq("标题改成 78787"));
        verify(executionTool).interruptExecution("task-1", "interrupt execution for plan adjustment");
        verify(executionTool, never()).confirmExecution(any());
    }

    @Test
    void interruptOnlyAdjustmentThatReturnsAskUserMarksExecutingPlanAdjustmentPendingType() {
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
        PlanTaskSession replanning = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.INTERRUPTING)
                .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder().build())
                .build();
        PlanTaskSession askUser = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder()
                        .assistantReply("请问您希望如何调整计划？")
                        .build())
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.get("task-1")).thenReturn(executing, replanning);
        when(intakeService.decide(executing, "中断一下", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "中断一下",
                        "interrupt current execution",
                        null,
                        null,
                        AdjustmentTargetEnum.RUNNING_PLAN
                ));
        when(executionTool.interruptExecution("task-1", "interrupt execution for plan adjustment"))
                .thenReturn(PlannerToolResult.success("task-1", PlanningPhaseEnum.INTERRUPTING, "execution interrupted", null));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"), eq("中断一下"), eq(workspaceContext), eq("中断一下")))
                .thenReturn(askUser);

        PlanTaskSession result = service.handlePlanRequest("中断一下", workspaceContext, null, null);

        assertThat(result).isSameAs(askUser);
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ASK_USER);
        assertThat(result.getIntakeState().getPendingInteractionType()).isEqualTo(PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT);
        verify(executionTool).interruptExecution("task-1", "interrupt execution for plan adjustment");
        verify(executionTool, never()).confirmExecution(any());
    }

    @Test
    void askUserExecutingPlanAdjustmentTreatsConcreteEditAsClarificationReplyInsteadOfCompletedTaskSelection() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .chatId("chat-1")
                .inputSource("LARK_GROUP")
                .build();
        PlanTaskSession askUser = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder()
                        .pendingInteractionType(PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT)
                        .assistantReply("请问您希望如何调整计划？")
                        .build())
                .build();
        PlanTaskSession ready = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.get("task-1")).thenReturn(askUser);
        when(intakeService.decide(askUser, "帮我把标题改成9191", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "帮我把标题改成9191",
                        "completed artifact edit",
                        null,
                        null,
                        AdjustmentTargetEnum.COMPLETED_ARTIFACT
                ));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"), eq("帮我把标题改成9191"), eq(workspaceContext), eq(null)))
                .thenReturn(ready);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("帮我把标题改成9191", workspaceContext, null, null);

        assertThat(result).isSameAs(ready);
        verify(graphRunner).run(any(PlannerSupervisorDecision.class), eq("task-1"), eq("帮我把标题改成9191"), eq(workspaceContext), eq(null));
        verify(taskBridgeService).ensureTask(ready);
        verify(resolver, never()).resolveCompletedCandidates(workspaceContext);
    }

    @Test
    void askUserExecutingPlanAdjustmentDoesNotRouteNumericReplyToCompletedTaskSelection() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .chatId("chat-1")
                .inputSource("LARK_GROUP")
                .build();
        PlanTaskSession askUser = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder()
                        .pendingInteractionType(PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT)
                        .assistantReply("请问您希望如何调整计划？")
                        .build())
                .build();
        PlanTaskSession stillAskUser = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.get("task-1")).thenReturn(askUser);
        when(intakeService.decide(askUser, "1", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "1",
                        "completed artifact edit selection candidate",
                        null,
                        null,
                        AdjustmentTargetEnum.COMPLETED_ARTIFACT
                ));
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"), eq("1"), eq(workspaceContext), eq(null)))
                .thenReturn(stillAskUser);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                new PlannerConversationMemoryService(new PlannerProperties()),
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("1", workspaceContext, null, null);

        assertThat(result).isSameAs(stillAskUser);
        verify(graphRunner).run(any(PlannerSupervisorDecision.class), eq("task-1"), eq("1"), eq(workspaceContext), eq(null));
        verify(resolver, never()).resolveCompletedCandidates(workspaceContext);
    }
}
