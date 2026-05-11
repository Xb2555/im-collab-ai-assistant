package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PendingTaskCandidate;
import com.lark.imcollab.common.model.entity.PendingArtifactCandidate;
import com.lark.imcollab.common.model.entity.PendingArtifactSelection;
import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.entity.PendingTaskSelection;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.facade.DocumentEditIntentFacade;
import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlannerConversationCompletedSelectionTest {

    @Test
    void explicitNewTaskWithAdjustmentWordsDoesNotRouteToCompletedSelection() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_GROUP_CHAT")
                .chatId("chat-1")
                .threadId("thread-1")
                .senderOpenId("ou-1")
                .build();
        String instruction = "新建一个任务：直接创建一份2页PPT，主题是功能验证；第2页要点：原地修改、整体重跑。";
        PlanTaskSession newSession = PlanTaskSession.builder()
                .taskId("new-task")
                .build();
        PlanTaskSession planned = PlanTaskSession.builder()
                .taskId("new-task")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("new-task", false, "LARK_GROUP_CHAT:chat-1:thread-1"));
        when(intakeService.decide(any(), eq(instruction), isNull(), eq(false)))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.NEW_TASK, instruction, "new task", null));
        when(sessionService.getOrCreate("new-task")).thenReturn(newSession);
        when(graphRunner.run(any(), eq("new-task"), eq(instruction), eq(context), isNull()))
                .thenReturn(planned);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest(instruction, context, null, null);

        assertThat(result).isSameAs(planned);
        verify(resolver, never()).resolveCompletedCandidates(context);
        verify(graphRunner).run(any(), eq("new-task"), eq(instruction), eq(context), isNull());
        verify(taskBridgeService).ensureTask(planned);
    }

    @Test
    void multipleCompletedTasksReturnTransientSelectionInsteadOfAdjustingImmediately() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_GROUP_CHAT")
                .chatId("chat-1")
                .threadId("thread-1")
                .senderOpenId("ou-1")
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("selector-task", false, "LARK_GROUP_CHAT:chat-1:thread-1"));
        when(intakeService.decide(any(), eq("把刚才那个 PPT 第二页标题改一下"), isNull(), eq(false)))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "把刚才那个 PPT 第二页标题改一下",
                        "model classified completed task adjustment",
                        null));
        when(resolver.conversationKey(context)).thenReturn("LARK_GROUP_CHAT:chat-1:thread-1");
        when(resolver.resolveCompletedCandidates(context)).thenReturn(List.of(
                candidate("task-1", "采购评审 PPT"),
                candidate("task-2", "项目周报 PPT")
        ));
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("把刚才那个 PPT 第二页标题改一下", context, null, null);

        assertThat(result.getTaskId()).isEqualTo("selector-task");
        assertThat(result.getIntakeState().getAssistantReply()).contains("我找到多个已完成任务").contains("采购评审 PPT");
        assertThat(result.getIntakeState().getPendingTaskSelection().getCandidates()).hasSize(2);
        verify(graphRunner, org.mockito.Mockito.never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void completedTaskListQueryReturnsSelectionListWithoutRunningAdjustment() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("selector-task", false, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(resolver.conversationKey(context)).thenReturn("LARK_PRIVATE_CHAT:chat-1:chat-root");
        when(intakeService.decide(any(), eq("我想看看这个会话里已完成的任务"), isNull(), eq(false)))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.STATUS_QUERY,
                        "我想看看这个会话里已完成的任务",
                        "llm completed task list query",
                        null,
                        "COMPLETED_TASKS"));
        when(resolver.resolveCompletedCandidates(context)).thenReturn(List.of(
                candidate("task-1", "采购评审 PPT"),
                candidate("task-2", "项目周报 DOC")
        ));
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("我想看看这个会话里已完成的任务", context, null, null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.INTAKE);
        assertThat(result.getIntakeState().getPendingTaskSelection()).isNotNull();
        assertThat(result.getIntakeState().getPendingTaskSelection().getSelectionPurpose()).isEqualTo("COMPLETED_TASK_LIST");
        assertThat(result.getIntakeState().getAssistantReply())
                .contains("我找到这些已完成任务")
                .doesNotContain("task-1")
                .contains("创建于 2026-05-10 10:30")
                .contains("更新于 2026-05-10 11:30")
                .contains("回复编号即可");
        verify(graphRunner, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void selectedCompletedTaskAdjustmentAppendsInferredPptArtifactId() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession pending = PlanTaskSession.builder()
                .taskId("selector-task")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.UNKNOWN)
                        .pendingTaskSelection(PendingTaskSelection.builder()
                                .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                                .originalInstruction("把第二页标题改成项目总结")
                                .selectionPurpose("COMPLETED_TASK_ADJUSTMENT")
                                .candidates(List.of(candidate("task-ppt", "项目汇报 PPT")))
                                .expiresAt(Instant.now().plusSeconds(60))
                                .build())
                        .build())
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-ppt")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("selector-task", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("selector-task")).thenReturn(pending);
        when(sessionService.get("task-ppt")).thenReturn(completed);
        when(resolver.inferEditableArtifact("task-ppt", "把第二页标题改成项目总结"))
                .thenReturn(java.util.Optional.of(ArtifactRecord.builder()
                        .artifactId("artifact-ppt-9")
                        .type(ArtifactTypeEnum.PPT)
                        .build()));
        when(graphRunner.run(any(), eq("task-ppt"), eq("把第二页标题改成项目总结\n目标产物ID：artifact-ppt-9"), eq(context), isNull()))
                .thenReturn(completed);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("1", context, null, null);

        assertThat(result).isSameAs(completed);
        verify(graphRunner).run(any(), eq("task-ppt"), eq("把第二页标题改成项目总结\n目标产物ID：artifact-ppt-9"), eq(context), isNull());
        verify(taskBridgeService).ensureTask(completed);
    }

    @Test
    void repeatedCompletedTaskListQueryDuringPendingSelectionReplaysCandidateList() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession pending = PlanTaskSession.builder()
                .taskId("selector-task")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.UNKNOWN)
                        .pendingTaskSelection(PendingTaskSelection.builder()
                                .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                                .originalInstruction("已完成任务有哪些")
                                .selectionPurpose("COMPLETED_TASK_LIST")
                                .candidates(List.of(candidate("task-1", "项目汇报 DOC")))
                                .expiresAt(Instant.now().plusSeconds(60))
                                .build())
                        .build())
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("selector-task", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("selector-task")).thenReturn(pending);
        when(intakeService.decide(pending, "已完成任务有哪些", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.STATUS_QUERY,
                        "已完成任务有哪些",
                        "completed task list query",
                        null,
                        "COMPLETED_TASKS"));
        when(resolver.conversationKey(context)).thenReturn("LARK_PRIVATE_CHAT:chat-1:chat-root");
        when(resolver.resolveCompletedCandidates(context)).thenReturn(List.of(candidate("task-1", "项目汇报 DOC")));
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("已完成任务有哪些", context, null, null);

        assertThat(result.getIntakeState().getAssistantReply())
                .contains("我找到这些已完成任务")
                .doesNotContain("我还没识别出要选哪一个");
        assertThat(result.getIntakeState().getPendingTaskSelection()).isNotNull();
        verify(graphRunner, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void numericReplyPrefersPendingTaskSelectionOverFollowUpRecommendation() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession pending = PlanTaskSession.builder()
                .taskId("selector-task")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.UNKNOWN)
                        .pendingTaskSelection(PendingTaskSelection.builder()
                                .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                                .originalInstruction("已完成任务有哪些")
                                .selectionPurpose("COMPLETED_TASK_LIST")
                                .candidates(List.of(candidate("task-1", "项目汇报 DOC")))
                                .expiresAt(Instant.now().plusSeconds(60))
                                .build())
                        .build())
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation recommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_SHAREABLE_SUMMARY")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.SUMMARY)
                .plannerInstruction("保留现有产物，新增一段可直接发送的任务摘要。")
                .suggestedUserInstruction("基于当前任务内容生成一段可直接发送的摘要")
                .priority(1)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("selector-task", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("selector-task")).thenReturn(pending);
        when(sessionService.get("task-1")).thenReturn(completed);
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(java.util.Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("selector-task")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(List.of(recommendation))
                        .build()
        ));
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner,
                null,
                null,
                null,
                conversationTaskStateService,
                matcher
        );

        PlanTaskSession result = service.handlePlanRequest("1", context, null, null);

        assertThat(result.getTaskId()).isEqualTo("task-1");
        assertThat(result.getIntakeState().getReadOnlyView()).isEqualTo("COMPLETED_TASKS");
        verifyNoInteractions(matcher);
        verify(graphRunner, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void explicitNewTaskInsideBoundConversationUsesFreshTaskId() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_GROUP_CHAT")
                .chatId("chat-1")
                .threadId("thread-1")
                .senderOpenId("ou-1")
                .build();
        String instruction = "新建一个任务：生成一份6页PPT，主题执行中修改拦截验证，每页写等待提示和重跑验证。";
        PlanTaskSession oldSession = PlanTaskSession.builder()
                .taskId("old-task")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("old-task", true, "LARK_GROUP_CHAT:chat-1:thread-1"));
        when(sessionService.get("old-task")).thenReturn(oldSession);
        when(intakeService.decide(oldSession, instruction, null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.NEW_TASK, instruction, "explicit fresh task", null));
        when(sessionService.getOrCreate(anyString())).thenAnswer(invocation -> PlanTaskSession.builder()
                .taskId(invocation.getArgument(0, String.class))
                .build());
        when(graphRunner.run(any(), anyString(), eq(instruction), eq(context), isNull()))
                .thenAnswer(invocation -> PlanTaskSession.builder()
                        .taskId(invocation.getArgument(1, String.class))
                        .planningPhase(PlanningPhaseEnum.PLAN_READY)
                        .build());
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest(instruction, context, null, null);

        assertThat(result.getTaskId()).isNotEqualTo("old-task");
        ArgumentCaptor<TaskSessionResolution> binding = ArgumentCaptor.forClass(TaskSessionResolution.class);
        verify(resolver).bindConversation(binding.capture());
        assertThat(binding.getValue().taskId()).isEqualTo(result.getTaskId());
        verify(graphRunner).run(any(), eq(result.getTaskId()), eq(instruction), eq(context), isNull());
        verify(taskBridgeService).ensureTask(result);
    }

    @Test
    void completedAdjustmentUsesActiveTaskAnchorInsteadOfPromptingSelection() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_GROUP_CHAT")
                .chatId("chat-1")
                .threadId("thread-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_GROUP_CHAT:chat-1:thread-1"));
        when(resolver.conversationState(context)).thenReturn(java.util.Optional.of(ConversationTaskState.builder()
                .conversationKey("LARK_GROUP_CHAT:chat-1:thread-1")
                .activeTaskId("task-1")
                .lastCompletedTaskId("task-1")
                .build()));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(intakeService.decide(completed, "把第三页改成实施收益", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, "把第三页改成实施收益", "completed active task", null));
        when(resolver.resolveCompletedCandidates(context)).thenReturn(List.of(candidate("task-1", "采购评审 PPT")));
        when(graphRunner.run(any(), eq("task-1"), eq("把第三页改成实施收益"), eq(context), isNull()))
                .thenReturn(completed);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("把第三页改成实施收益", context, null, null);

        assertThat(result).isSameAs(completed);
        verify(resolver, never()).resolveCompletedCandidates(context);
        verify(graphRunner).run(any(), eq("task-1"), eq("把第三页改成实施收益"), eq(context), isNull());
    }

    @Test
    void currentCompletedDocAppendUsesCurrentTaskDirectlyWithoutPromptingSelection() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-doc")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PlanTaskSession freshTask = PlanTaskSession.builder()
                .taskId("task-new")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-doc", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(resolver.conversationState(context)).thenReturn(java.util.Optional.of(ConversationTaskState.builder()
                .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                .activeTaskId("task-doc")
                .lastCompletedTaskId("task-doc")
                .build()));
        when(sessionService.get("task-doc")).thenReturn(completed);
        when(intakeService.decide(completed, "再加一小节关于项目总结的内容", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "再加一小节关于项目总结的内容",
                        "completed current doc edit",
                        null));
        when(resolver.hasEditableArtifacts("task-doc")).thenReturn(true);
        when(resolver.inferEditableArtifact("task-doc", "再加一小节关于项目总结的内容"))
                .thenReturn(java.util.Optional.of(ArtifactRecord.builder()
                        .artifactId("artifact-doc-1")
                        .type(ArtifactTypeEnum.DOC)
                        .build()));
        when(graphRunner.run(any(), eq("task-doc"), eq("再加一小节关于项目总结的内容\n目标产物ID：artifact-doc-1"), eq(context), isNull()))
                .thenReturn(completed);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("再加一小节关于项目总结的内容", context, null, null);

        assertThat(result).isSameAs(completed);
        verify(resolver, never()).resolveCompletedCandidates(context);
        verify(graphRunner).run(any(), eq("task-doc"), eq("再加一小节关于项目总结的内容\n目标产物ID：artifact-doc-1"), eq(context), isNull());
    }

    @Test
    void currentCompletedPptEditUsesCurrentTaskDirectlyWithoutPromptingSelection() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-ppt")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-ppt", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(resolver.conversationState(context)).thenReturn(java.util.Optional.of(ConversationTaskState.builder()
                .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                .activeTaskId("task-ppt")
                .lastCompletedTaskId("task-ppt")
                .build()));
        when(sessionService.get("task-ppt")).thenReturn(completed);
        when(intakeService.decide(completed, "把第二页标题改成项目总结", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "把第二页标题改成项目总结",
                        "completed current ppt edit",
                        null));
        when(resolver.hasEditableArtifacts("task-ppt")).thenReturn(true);
        when(resolver.inferEditableArtifact("task-ppt", "把第二页标题改成项目总结"))
                .thenReturn(java.util.Optional.of(ArtifactRecord.builder()
                        .artifactId("artifact-ppt-1")
                        .type(ArtifactTypeEnum.PPT)
                        .build()));
        when(graphRunner.run(any(), eq("task-ppt"), eq("把第二页标题改成项目总结\n目标产物ID：artifact-ppt-1"), eq(context), isNull()))
                .thenReturn(completed);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("把第二页标题改成项目总结", context, null, null);

        assertThat(result).isSameAs(completed);
        verify(resolver, never()).resolveCompletedCandidates(context);
        verify(graphRunner).run(any(), eq("task-ppt"), eq("把第二页标题改成项目总结\n目标产物ID：artifact-ppt-1"), eq(context), isNull());
    }

    @Test
    void selectedCompletedTaskFollowupAdjustmentDoesNotPromptTaskSelectionAgain() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-2")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-2", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(resolver.conversationState(context)).thenReturn(java.util.Optional.of(ConversationTaskState.builder()
                .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                .activeTaskId("task-2")
                .lastCompletedTaskId("task-2")
                .build()));
        when(sessionService.get("task-2")).thenReturn(completed);
        when(intakeService.decide(completed, "把第三页改成实施收益", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "把第三页改成实施收益",
                        "completed selected task followup",
                        null));
        when(graphRunner.run(any(), eq("task-2"), eq("把第三页改成实施收益"), eq(context), isNull()))
                .thenReturn(completed);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("把第三页改成实施收益", context, null, null);

        assertThat(result).isSameAs(completed);
        verify(resolver, never()).resolveCompletedCandidates(context);
        verify(graphRunner).run(any(), eq("task-2"), eq("把第三页改成实施收益"), eq(context), isNull());
        verify(taskBridgeService).ensureTask(completed);
    }

    @Test
    void selectionReplyRestoresOriginalInstructionWithChosenTask() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_GROUP_CHAT")
                .chatId("chat-1")
                .threadId("thread-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession pending = PlanTaskSession.builder()
                .taskId("selector-task")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.UNKNOWN)
                        .pendingTaskSelection(PendingTaskSelection.builder()
                                .conversationKey("LARK_GROUP_CHAT:chat-1:thread-1")
                                .originalInstruction("把刚才那个 PPT 第二页标题改一下")
                                .candidates(List.of(candidate("task-1", "采购评审 PPT"), candidate("task-2", "项目周报 PPT")))
                                .expiresAt(Instant.now().plusSeconds(60))
                                .build())
                        .build())
                .build();
        PlanTaskSession adjusted = PlanTaskSession.builder()
                .taskId("task-2")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("selector-task", true, "LARK_GROUP_CHAT:chat-1:thread-1"));
        when(sessionService.get("selector-task")).thenReturn(pending);
        when(graphRunner.run(any(), eq("task-2"), eq("把刚才那个 PPT 第二页标题改一下"), eq(context), isNull()))
                .thenReturn(adjusted);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("@_user_1 2", context, null, null);

        assertThat(result).isSameAs(adjusted);
        verify(resolver).bindConversation(new TaskSessionResolution("task-2", true, "LARK_GROUP_CHAT:chat-1:thread-1"));
        verify(graphRunner).run(any(), eq("task-2"), eq("把刚才那个 PPT 第二页标题改一下"), eq(context), isNull());
        verify(taskBridgeService).ensureTask(adjusted);
    }

    @Test
    void completedTaskListSelectionSwitchesTaskWithoutRunningAdjustment() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession pending = PlanTaskSession.builder()
                .taskId("selector-task")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.UNKNOWN)
                        .pendingTaskSelection(PendingTaskSelection.builder()
                                .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                                .originalInstruction("我想看看这个会话里已完成的任务")
                                .selectionPurpose("COMPLETED_TASK_LIST")
                                .candidates(List.of(candidate("task-1", "采购评审 PPT"), candidate("task-2", "项目周报 PPT")))
                                .expiresAt(Instant.now().plusSeconds(60))
                                .build())
                        .build())
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-2")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("selector-task", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("selector-task")).thenReturn(pending);
        when(sessionService.get("task-2")).thenReturn(completed);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("2", context, null, null);

        assertThat(result.getTaskId()).isEqualTo("task-2");
        assertThat(result.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.STATUS_QUERY);
        assertThat(result.getIntakeState().getReadOnlyView()).isEqualTo("COMPLETED_TASKS");
        assertThat(result.getIntakeState().getAssistantReply()).contains("已切换到这个已完成任务").contains("可修改产物类型");
        verify(resolver).bindConversation(new TaskSessionResolution("task-2", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        verify(graphRunner, never()).run(any(), any(), any(), any(), any());
        verify(taskBridgeService, never()).ensureTask(any());
    }

    @Test
    void forcedNewTaskBypassesExpiredPendingSelection() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        String instruction = "新建一个任务：生成一份2页PPT计划";
        PlanTaskSession pending = PlanTaskSession.builder()
                .taskId("selector-task")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.UNKNOWN)
                        .pendingTaskSelection(PendingTaskSelection.builder()
                                .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                                .originalInstruction("把第三页改一下")
                                .candidates(List.of(candidate("task-1", "采购评审 PPT")))
                                .expiresAt(Instant.now().minusSeconds(60))
                                .build())
                        .build())
                .build();
        PlanTaskSession newSession = PlanTaskSession.builder()
                .taskId("new-task")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build();
        PlanTaskSession planned = PlanTaskSession.builder()
                .taskId("new-task")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("selector-task", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("selector-task")).thenReturn(pending);
        when(intakeService.decide(pending, instruction, null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.NEW_TASK, instruction, "hard rule force new task", null));
        when(intakeService.isForcedNewTaskDecision(any())).thenReturn(true);
        when(sessionService.getOrCreate(anyString())).thenReturn(newSession);
        when(graphRunner.run(any(), eq("new-task"), eq(instruction), eq(context), isNull())).thenReturn(planned);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest(instruction, context, null, null);

        assertThat(result).isSameAs(planned);
        verify(graphRunner).run(any(), eq("new-task"), eq(instruction), eq(context), isNull());
        verify(taskBridgeService).ensureTask(planned);
    }

    @Test
    void artifactSelectionReplyRestoresOriginalInstructionWithTargetArtifact() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_GROUP_CHAT")
                .chatId("chat-1")
                .threadId("thread-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession pending = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT)
                        .pendingAdjustmentInstruction("把第2页标题改成新标题")
                        .pendingArtifactSelection(PendingArtifactSelection.builder()
                                .conversationKey("LARK_GROUP_CHAT:chat-1:thread-1")
                                .taskId("task-1")
                                .originalInstruction("把第2页标题改成新标题")
                                .candidates(List.of(
                                        artifactCandidate("artifact-ppt-1", "旧版 PPT"),
                                        artifactCandidate("artifact-ppt-2", "新版 PPT")))
                                .expiresAt(Instant.now().plusSeconds(60))
                                .build())
                        .build())
                .build();
        PlanTaskSession adjusted = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_GROUP_CHAT:chat-1:thread-1"));
        when(sessionService.get("task-1")).thenReturn(pending);
        when(graphRunner.run(any(), eq("task-1"), eq("把第2页标题改成新标题\n目标产物ID：artifact-ppt-2"), eq(context), isNull()))
                .thenReturn(adjusted);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("@_user_1 2", context, null, null);

        assertThat(result).isSameAs(adjusted);
        verify(sessionService).saveWithoutVersionChange(pending);
        verify(graphRunner).run(any(), eq("task-1"), eq("把第2页标题改成新标题\n目标产物ID：artifact-ppt-2"), eq(context), isNull());
        verify(taskBridgeService).ensureTask(adjusted);
    }

    @Test
    void completedCurrentTaskRecoversDocArtifactEditWhenClassifierSaysNewTask() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        DocumentEditIntentFacade documentEditIntentFacade = mock(DocumentEditIntentFacade.class);
        PresentationEditIntentFacade presentationEditIntentFacade = mock(PresentationEditIntentFacade.class);
        CompletedArtifactIntentRecoveryService recoveryService = new CompletedArtifactIntentRecoveryService(
                resolver,
                provider(documentEditIntentFacade),
                provider(presentationEditIntentFacade)
        );
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-doc")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-doc", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-doc")).thenReturn(completed);
        when(intakeService.decide(completed, "加一小节关于ggbond的内容，随意编造即可", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.NEW_TASK,
                        "加一小节关于ggbond的内容，随意编造即可",
                        "llm misclassified as standalone task",
                        null));
        when(resolver.conversationState(context)).thenReturn(java.util.Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-doc")
                        .lastCompletedTaskId("task-doc")
                        .build()
        ));
        when(resolver.hasEditableArtifacts("task-doc")).thenReturn(true);
        when(resolver.resolveEditableArtifacts("task-doc")).thenReturn(List.of(ArtifactRecord.builder()
                .artifactId("artifact-doc-1")
                .taskId("task-doc")
                .type(ArtifactTypeEnum.DOC)
                .url("https://doc.example/1")
                .build()));
        when(resolver.inferEditableArtifact("task-doc", "加一小节关于ggbond的内容，随意编造即可"))
                .thenReturn(java.util.Optional.of(ArtifactRecord.builder()
                        .artifactId("artifact-doc-1")
                        .taskId("task-doc")
                        .type(ArtifactTypeEnum.DOC)
                        .url("https://doc.example/1")
                        .build()));
        when(documentEditIntentFacade.resolve(eq("加一小节关于ggbond的内容，随意编造即可"), eq(context)))
                .thenReturn(DocumentEditIntent.builder()
                        .clarificationNeeded(false)
                        .build());
        PlanTaskSession adjusted = PlanTaskSession.builder()
                .taskId("task-doc")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(graphRunner.run(any(), eq("task-doc"),
                eq("加一小节关于ggbond的内容，随意编造即可\n目标产物ID：artifact-doc-1"),
                eq(context), isNull()))
                .thenReturn(adjusted);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner,
                recoveryService
        );

        PlanTaskSession result = service.handlePlanRequest("加一小节关于ggbond的内容，随意编造即可", context, null, null);

        assertThat(result).isSameAs(adjusted);
        verify(graphRunner).run(any(), eq("task-doc"),
                eq("加一小节关于ggbond的内容，随意编造即可\n目标产物ID：artifact-doc-1"),
                eq(context), isNull());
        verify(taskBridgeService).ensureTask(adjusted);
    }

    @Test
    void completedCurrentTaskDoesNotRecoverExplicitFreshTaskRequest() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        DocumentEditIntentFacade documentEditIntentFacade = mock(DocumentEditIntentFacade.class);
        PresentationEditIntentFacade presentationEditIntentFacade = mock(PresentationEditIntentFacade.class);
        CompletedArtifactIntentRecoveryService recoveryService = new CompletedArtifactIntentRecoveryService(
                resolver,
                provider(documentEditIntentFacade),
                provider(presentationEditIntentFacade)
        );
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-doc")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-doc", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-doc")).thenReturn(completed);
        when(sessionService.getOrCreate(anyString())).thenAnswer(invocation -> PlanTaskSession.builder()
                .taskId(invocation.getArgument(0, String.class))
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build());
        when(intakeService.decide(completed, "新建一个任务，生成一份新的项目文档", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.NEW_TASK,
                        "新建一个任务，生成一份新的项目文档",
                        "standalone new task",
                        null));
        when(resolver.conversationState(context)).thenReturn(java.util.Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-doc")
                        .lastCompletedTaskId("task-doc")
                        .build()
        ));
        when(resolver.hasEditableArtifacts("task-doc")).thenReturn(true);
        PlanTaskSession freshTask = PlanTaskSession.builder()
                .taskId("task-new")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();
        ArgumentCaptor<String> taskIdCaptor = ArgumentCaptor.forClass(String.class);
        when(graphRunner.run(any(), taskIdCaptor.capture(),
                eq("新建一个任务，生成一份新的项目文档"),
                eq(context), isNull()))
                .thenReturn(freshTask);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner,
                recoveryService
        );

        PlanTaskSession result = service.handlePlanRequest("新建一个任务，生成一份新的项目文档", context, null, null);

        assertThat(result).isSameAs(freshTask);
        assertThat(taskIdCaptor.getValue()).isNotEqualTo("task-doc");
        verify(taskBridgeService).ensureTask(freshTask);
    }

    private static <T> ObjectProvider<T> provider(T facade) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return facade;
            }

            @Override
            public T getIfAvailable() {
                return facade;
            }

            @Override
            public T getIfUnique() {
                return facade;
            }

            @Override
            public T getObject() {
                return facade;
            }

            @Override
            public java.util.Iterator<T> iterator() {
                return java.util.List.of(facade).iterator();
            }

            @Override
            public java.util.stream.Stream<T> stream() {
                return java.util.stream.Stream.of(facade);
            }

            @Override
            public java.util.stream.Stream<T> orderedStream() {
                return java.util.stream.Stream.of(facade);
            }
        };
    }

    @Test
    void completedBoundConversationStillListsMultipleCandidatesInsteadOfDefaulting() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_GROUP_CHAT")
                .chatId("chat-1")
                .threadId("thread-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_GROUP_CHAT:chat-1:thread-1"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(intakeService.decide(completed, "把刚才那个 PPT 第二页标题改一下", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "把刚才那个 PPT 第二页标题改一下",
                        "model classified completed task adjustment",
                        null));
        when(resolver.conversationKey(context)).thenReturn("LARK_GROUP_CHAT:chat-1:thread-1");
        when(resolver.resolveCompletedCandidates(context)).thenReturn(List.of(
                candidate("task-1", "采购评审 PPT"),
                candidate("task-2", "项目周报 PPT")
        ));
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("把刚才那个 PPT 第二页标题改一下", context, null, null);

        assertThat(result.getTaskId()).isNotEqualTo("task-1");
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.INTAKE);
        assertThat(result.getIntakeState().getPendingTaskSelection().getCandidates()).hasSize(2);
        assertThat(completed.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        verify(graphRunner, org.mockito.Mockito.never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void executingBoundModificationWithoutExecutionBridgeKeepsCurrentTaskAndExplainsInterruptFailure() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_GROUP_CHAT")
                .chatId("chat-1")
                .threadId("thread-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession executing = PlanTaskSession.builder()
                .taskId("running-task")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("running-task", true, "LARK_GROUP_CHAT:chat-1:thread-1"));
        when(sessionService.get("running-task")).thenReturn(executing);
        when(intakeService.decide(executing, "把刚才那个 PPT 第二页标题改一下", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "把刚才那个 PPT 第二页标题改一下",
                        "classified by existing planner intake",
                        null));
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("把刚才那个 PPT 第二页标题改一下", context, null, null);

        assertThat(result.getTaskId()).isEqualTo("running-task");
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.EXECUTING);
        assertThat(result.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        assertThat(result.getIntakeState().getAssistantReply()).contains("当前执行还没成功中断").contains("先不进入重规划");
        assertThat(executing.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.EXECUTING);
        verify(sessionService, org.mockito.Mockito.times(3)).saveWithoutVersionChange(executing);
        verify(resolver, never()).resolveCompletedCandidates(context);
        verify(intakeService).decide(executing, "把刚才那个 PPT 第二页标题改一下", null, true);
        verify(graphRunner, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void executingBoundCancelCommandStillGoesThroughNormalCancelIntent() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_GROUP_CHAT")
                .chatId("chat-1")
                .threadId("thread-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession executing = PlanTaskSession.builder()
                .taskId("running-task")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();
        PlanTaskSession aborted = PlanTaskSession.builder()
                .taskId("running-task")
                .planningPhase(PlanningPhaseEnum.ABORTED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("running-task", true, "LARK_GROUP_CHAT:chat-1:thread-1"));
        when(sessionService.get("running-task")).thenReturn(executing);
        when(intakeService.decide(executing, "取消当前任务", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.CANCEL_TASK, "取消当前任务", "hard rule cancel", null));
        when(graphRunner.run(any(), eq("running-task"), eq("取消当前任务"), eq(context), isNull()))
                .thenReturn(aborted);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("取消当前任务", context, null, null);

        assertThat(result).isSameAs(aborted);
        verify(graphRunner).run(any(), eq("running-task"), eq("取消当前任务"), eq(context), isNull());
        verify(taskBridgeService).ensureTask(aborted);
    }

    @Test
    void singleCompletedCandidateRefreshesInputContextBeforeAdjustment() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_GROUP_CHAT")
                .chatId("chat-1")
                .threadId("thread-1")
                .messageId("current-message")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_GROUP_CHAT:chat-1:thread-1"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(intakeService.decide(completed, "把刚才那个 PPT 第二页标题改一下", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "把刚才那个 PPT 第二页标题改一下",
                        "model classified completed task adjustment",
                        null));
        when(resolver.resolveCompletedCandidates(context)).thenReturn(List.of(candidate("task-1", "采购评审 PPT")));
        when(graphRunner.run(any(), eq("task-1"), eq("把刚才那个 PPT 第二页标题改一下"), eq(context), isNull()))
                .thenReturn(completed);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        service.handlePlanRequest("把刚才那个 PPT 第二页标题改一下", context, null, null);

        assertThat(completed.getInputContext().getMessageId()).isEqualTo("current-message");
        assertThat(completed.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        verify(sessionService).saveWithoutVersionChange(completed);
        verify(graphRunner).run(any(), eq("task-1"), eq("把刚才那个 PPT 第二页标题改一下"), eq(context), isNull());
    }

    @Test
    void executingSessionWithCompletedArtifactTargetIsRejectedDuringExecution() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_GROUP_CHAT")
                .chatId("chat-1")
                .threadId("thread-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession executing = PlanTaskSession.builder()
                .taskId("running-task")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("running-task", true, "LARK_GROUP_CHAT:chat-1:thread-1"));
        when(sessionService.get("running-task")).thenReturn(executing);
        when(intakeService.decide(executing, "把刚生成的 PPT 第二页标题改一下", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "把刚生成的 PPT 第二页标题改一下",
                        "completed artifact edit",
                        null,
                        null,
                        AdjustmentTargetEnum.COMPLETED_ARTIFACT
                ));
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner
        );

        PlanTaskSession result = service.handlePlanRequest("把刚生成的 PPT 第二页标题改一下", context, null, null);

        assertThat(result).isSameAs(executing);
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.EXECUTING);
        assertThat(result.getIntakeState().getAssistantReply()).contains("当前有任务正在执行，请等当前任务完成后再修改已有产物");
        assertThat(result.getIntakeState().getAdjustmentTarget()).isEqualTo(AdjustmentTargetEnum.COMPLETED_ARTIFACT);
        verify(resolver, never()).resolveCompletedCandidates(context);
        verify(graphRunner, never()).run(any(), any(), any(), any(), any());
        verify(taskBridgeService, never()).ensureTask(any());
    }

    @Test
    void selectingCompletedTaskFromListSyncsConversationActiveTask() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession pending = PlanTaskSession.builder()
                .taskId("selector-task")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.UNKNOWN)
                        .pendingTaskSelection(PendingTaskSelection.builder()
                                .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                                .originalInstruction("我想看看这个会话里已完成的任务")
                                .selectionPurpose("COMPLETED_TASK_LIST")
                                .candidates(List.of(candidate("task-1", "采购评审 PPT")))
                                .expiresAt(Instant.now().plusSeconds(60))
                                .build())
                        .build())
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("selector-task", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("selector-task")).thenReturn(pending);
        when(sessionService.get("task-1")).thenReturn(completed);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                taskBridgeService,
                memoryService,
                graphRunner,
                null,
                null,
                null,
                conversationTaskStateService,
                null
        );

        PlanTaskSession result = service.handlePlanRequest("1", context, null, null);

        assertThat(result.getTaskId()).isEqualTo("task-1");
        verify(conversationTaskStateService).syncFromSession(completed);
    }

    private PendingTaskCandidate candidate(String taskId, String title) {
        return PendingTaskCandidate.builder()
                .taskId(taskId)
                .title(title)
                .artifactTypes(title.contains("DOC") ? List.of(ArtifactTypeEnum.DOC) : List.of(ArtifactTypeEnum.PPT))
                .createdAt(Instant.parse("2026-05-10T02:30:00Z"))
                .updatedAt(Instant.parse("2026-05-10T03:30:00Z"))
                .build();
    }

    private PendingArtifactCandidate artifactCandidate(String artifactId, String title) {
        return PendingArtifactCandidate.builder()
                .artifactId(artifactId)
                .taskId("task-1")
                .type(ArtifactTypeEnum.PPT)
                .title(title)
                .updatedAt(Instant.now())
                .build();
    }
}
