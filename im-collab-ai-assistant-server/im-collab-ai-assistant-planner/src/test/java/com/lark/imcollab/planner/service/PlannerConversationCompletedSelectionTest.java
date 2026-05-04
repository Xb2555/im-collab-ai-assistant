package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PendingTaskCandidate;
import com.lark.imcollab.common.model.entity.PendingArtifactCandidate;
import com.lark.imcollab.common.model.entity.PendingArtifactSelection;
import com.lark.imcollab.common.model.entity.PendingTaskSelection;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    void executingBoundModificationReturnsTransientWaitWithoutMutatingRunningTask() {
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

        assertThat(result.getTaskId()).isNotEqualTo("running-task");
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.INTAKE);
        assertThat(result.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.UNKNOWN);
        assertThat(result.getIntakeState().getAssistantReply()).contains("当前任务还在执行中").contains("取消当前任务");
        assertThat(executing.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.EXECUTING);
        verify(sessionService, never()).saveWithoutVersionChange(executing);
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

    private PendingTaskCandidate candidate(String taskId, String title) {
        return PendingTaskCandidate.builder()
                .taskId(taskId)
                .title(title)
                .artifactTypes(List.of(ArtifactTypeEnum.PPT))
                .updatedAt(Instant.now())
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
