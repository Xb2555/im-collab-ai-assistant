package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.PendingCurrentTaskContinuationChoice;
import com.lark.imcollab.common.model.entity.PendingFollowUpConflictChoice;
import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.common.model.enums.PendingInteractionTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

class PlannerConversationFollowUpConflictChoiceTest {

    @Test
    void ambiguousNewTaskVersusFollowUpAsksUserConflictChoice() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        PendingFollowUpConflictArbiter arbiter = new PendingFollowUpConflictArbiter(matcher);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation pptRecommendation = pptRecommendation();
        PendingFollowUpRecommendation summaryRecommendation = summaryRecommendation();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(intakeService.decide(completed, "帮我整理一份材料", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.NEW_TASK, "帮我整理一份材料", "new task", null));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(List.of(pptRecommendation, summaryRecommendation))
                        .build()
        ));
        when(matcher.classifyCarryForwardCandidate("帮我整理一份材料", List.of(pptRecommendation, summaryRecommendation)))
                .thenReturn(PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED);
        when(matcher.match("帮我整理一份材料", List.of(pptRecommendation, summaryRecommendation), false, true))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.none());
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
                new CompletedArtifactIntentRecoveryService(resolver),
                null,
                conversationTaskStateService,
                matcher,
                null,
                null,
                arbiter
        );

        PlanTaskSession result = service.handlePlanRequest("帮我整理一份材料", context, null, null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ASK_USER);
        assertThat(result.getIntakeState().getPendingInteractionType()).isEqualTo(PendingInteractionTypeEnum.CURRENT_TASK_CONTINUATION_CHOICE);
        assertThat(result.getIntakeState().getPendingCurrentTaskContinuationChoice()).isNotNull();
        assertThat(result.getIntakeState().getPendingCurrentTaskContinuationChoice().getSelectedRecommendationId()).isNull();
        assertThat(result.getIntakeState().getPendingCurrentTaskContinuationChoice().getCandidateRecommendationIds())
                .containsExactly("GENERATE_PPT_FROM_DOC", "GENERATE_SHAREABLE_SUMMARY");
        assertThat(result.getIntakeState().getAssistantReply())
                .contains("再选具体后续动作")
                .contains("回复 1 新开任务，回复 2 继续当前任务");
        verifyNoInteractions(graphRunner);
    }

    @Test
    void ambiguousPlanAdjustmentVersusFollowUpAsksUserConflictChoice() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        PendingFollowUpConflictArbiter arbiter = new PendingFollowUpConflictArbiter(matcher);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation summaryRecommendation = summaryRecommendation();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(intakeService.decide(completed, "整理一下材料", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, "整理一下材料", "ambiguous adjustment", null));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(List.of(summaryRecommendation))
                        .build()
        ));
        when(matcher.classifyCarryForwardCandidate("整理一下材料", List.of(summaryRecommendation)))
                .thenReturn(PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED);
        when(matcher.match("整理一下材料", List.of(summaryRecommendation), false, false))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.none());
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
                new CompletedArtifactIntentRecoveryService(resolver),
                null,
                conversationTaskStateService,
                matcher,
                null,
                null,
                arbiter
        );

        PlanTaskSession result = service.handlePlanRequest("整理一下材料", context, null, null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ASK_USER);
        assertThat(result.getIntakeState().getPendingInteractionType()).isEqualTo(PendingInteractionTypeEnum.CURRENT_TASK_CONTINUATION_CHOICE);
        assertThat(result.getIntakeState().getPendingCurrentTaskContinuationChoice()).isNotNull();
        assertThat(result.getIntakeState().getPendingCurrentTaskContinuationChoice().getCandidateRecommendationIds())
                .containsExactly("GENERATE_SHAREABLE_SUMMARY");
        assertThat(result.getIntakeState().getAssistantReply()).contains("回复 1 新开任务，回复 2 继续当前任务");
        verifyNoInteractions(graphRunner);
    }

    @Test
    void ambiguousMaterialRequestMisclassifiedAsCompletedArtifactStillAsksNewOrCurrentChoice() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        PendingFollowUpConflictArbiter arbiter = new PendingFollowUpConflictArbiter(matcher);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation summaryRecommendation = summaryRecommendation();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(resolver.isTaskCurrentInConversation("task-1", context)).thenReturn(true);
        when(resolver.hasEditableArtifacts("task-1")).thenReturn(true);
        when(intakeService.decide(completed, "帮我整理一下材料", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "帮我整理一下材料",
                        "llm misclassified vague material organization as artifact edit",
                        null,
                        null,
                        AdjustmentTargetEnum.COMPLETED_ARTIFACT
                ));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(List.of(summaryRecommendation))
                        .build()
        ));
        when(matcher.classifyCarryForwardCandidate("帮我整理一下材料", List.of(summaryRecommendation)))
                .thenReturn(PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED);
        when(matcher.match("帮我整理一下材料", List.of(summaryRecommendation), false, false))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.none());
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
                new CompletedArtifactIntentRecoveryService(resolver),
                null,
                conversationTaskStateService,
                matcher,
                null,
                null,
                arbiter
        );

        PlanTaskSession result = service.handlePlanRequest("帮我整理一下材料", context, null, null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ASK_USER);
        assertThat(result.getIntakeState().getPendingInteractionType()).isEqualTo(PendingInteractionTypeEnum.CURRENT_TASK_CONTINUATION_CHOICE);
        assertThat(result.getIntakeState().getAssistantReply()).contains("回复 1 新开任务，回复 2 继续当前任务");
        verifyNoInteractions(graphRunner);
    }

    @Test
    void ambiguousReportMaterialRequestOnSelectedCompletedTaskDoesNotFallIntoArtifactSelection() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        PendingFollowUpConflictArbiter arbiter = new PendingFollowUpConflictArbiter(matcher);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation summaryRecommendation = summaryRecommendation();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(resolver.isTaskCurrentInConversation("task-1", context)).thenReturn(true);
        when(resolver.hasEditableArtifacts("task-1")).thenReturn(true);
        when(intakeService.decide(completed, "帮我整理一下汇报材料", null, true))
                .thenReturn(new TaskIntakeDecision(
                        TaskIntakeTypeEnum.PLAN_ADJUSTMENT,
                        "帮我整理一下汇报材料",
                        "llm misclassified report material organization as completed artifact edit",
                        null,
                        null,
                        AdjustmentTargetEnum.COMPLETED_ARTIFACT
                ));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(List.of(summaryRecommendation))
                        .build()
        ));
        when(matcher.classifyCarryForwardCandidate("帮我整理一下汇报材料", List.of(summaryRecommendation)))
                .thenReturn(PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED);
        when(matcher.match("帮我整理一下汇报材料", List.of(summaryRecommendation), false, false))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.none());
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
                new CompletedArtifactIntentRecoveryService(resolver),
                null,
                conversationTaskStateService,
                matcher,
                null,
                null,
                arbiter
        );

        PlanTaskSession result = service.handlePlanRequest("帮我整理一下汇报材料", context, null, null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ASK_USER);
        assertThat(result.getIntakeState().getPendingInteractionType()).isEqualTo(PendingInteractionTypeEnum.CURRENT_TASK_CONTINUATION_CHOICE);
        assertThat(result.getIntakeState().getAssistantReply()).contains("回复 1 新开任务，回复 2 继续当前任务");
        verifyNoInteractions(graphRunner);
        verify(resolver, never()).resolveCompletedCandidates(context);
    }

    @Test
    void currentTaskChoiceTwoShowsRecommendationsAndEditableArtifacts() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        PendingFollowUpConflictArbiter arbiter = new PendingFollowUpConflictArbiter(matcher);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PendingFollowUpRecommendation pptRecommendation = pptRecommendation();
        PendingFollowUpRecommendation summaryRecommendation = summaryRecommendation();
        PlanTaskSession selector = PlanTaskSession.builder()
                .taskId("selector-task")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .intakeState(TaskIntakeState.builder()
                        .continuationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .pendingCurrentTaskContinuationChoice(PendingCurrentTaskContinuationChoice.builder()
                                .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                                .originalInstruction("帮我整理一份材料")
                                .newTaskInstruction("帮我整理一份材料")
                                .targetTaskId("task-1")
                                .candidateRecommendationIds(List.of("GENERATE_PPT_FROM_DOC", "GENERATE_SHAREABLE_SUMMARY"))
                                .expiresAt(Instant.now().plusSeconds(60))
                                .build())
                        .pendingInteractionType(PendingInteractionTypeEnum.CURRENT_TASK_CONTINUATION_CHOICE)
                        .build())
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .intakeState(TaskIntakeState.builder().build())
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("selector-task", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("selector-task")).thenReturn(selector);
        when(sessionService.get("task-1")).thenReturn(completed);
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(List.of(pptRecommendation, summaryRecommendation))
                        .build()
        ));
        when(resolver.resolveEditableArtifacts("task-1")).thenReturn(List.of(
                ArtifactRecord.builder().artifactId("doc-1").taskId("task-1").type(ArtifactTypeEnum.DOC).title("9191项目启动会纪要").url("https://doc.example/9191").build(),
                ArtifactRecord.builder().artifactId("ppt-1").taskId("task-1").type(ArtifactTypeEnum.PPT).title("9191项目启动会汇报").preview("preview").build()
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
                new CompletedArtifactIntentRecoveryService(resolver),
                null,
                conversationTaskStateService,
                matcher,
                null,
                null,
                arbiter
        );

        PlanTaskSession result = service.handlePlanRequest("2", context, null, null);

        assertThat(result).isSameAs(completed);
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        assertThat(result.getIntakeState().getAssistantReply())
                .contains("已切回这个已完成任务")
                .contains("推荐下一步")
                .contains("基于这份文档生成一版汇报PPT")
                .contains("基于当前任务内容生成一段可直接发送的摘要")
                .contains("修改已有产物")
                .contains("[DOC] 9191项目启动会纪要")
                .contains("[PPT] 9191项目启动会汇报")
                .contains("https://doc.example/9191")
                .contains("内容预览已生成，正式链接还在回流中。")
                .contains("直接说要改哪一页、哪一段或新增什么内容");
        assertThat(result.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.STATUS_QUERY);
        assertThat(result.getIntakeState().getReadOnlyView()).isEqualTo("COMPLETED_TASKS");
        verify(conversationTaskStateService).markPendingFollowUpAwaitingSelection("LARK_PRIVATE_CHAT:chat-1:chat-root", false);
        verify(conversationTaskStateService).syncFromSession(completed);
        verify(resolver).bindConversation(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        verifyNoInteractions(graphRunner);
    }

    @Test
    void conflictChoiceOneStartsFreshTaskAndClearsPendingFollowUpRecommendations() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        PendingFollowUpConflictArbiter arbiter = new PendingFollowUpConflictArbiter(matcher);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession selector = PlanTaskSession.builder()
                .taskId("selector-task")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .intakeState(TaskIntakeState.builder()
                        .continuationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .pendingFollowUpConflictChoice(PendingFollowUpConflictChoice.builder()
                                .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                                .originalInstruction("生成一份北京旅游的ppt")
                                .newTaskInstruction("生成一份北京旅游的ppt")
                                .selectedRecommendationId("GENERATE_DOC_FROM_PPT")
                                .expiresAt(Instant.now().plusSeconds(60))
                                .build())
                        .pendingInteractionType(PendingInteractionTypeEnum.FOLLOW_UP_CONFLICT_CHOICE)
                        .build())
                .build();
        when(resolver.resolve(null, context))
                .thenReturn(new TaskSessionResolution("selector-task", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(new TaskSessionResolution("selector-task", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("selector-task")).thenReturn(selector);
        when(intakeService.decide(selector, "生成一份北京旅游的ppt", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.NEW_TASK, "生成一份北京旅游的ppt", "new task", null));
        when(sessionService.getOrCreate(anyString())).thenAnswer(invocation -> PlanTaskSession.builder()
                .taskId(invocation.getArgument(0, String.class))
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build());
        when(graphRunner.run(any(), anyString(), eq("生成一份北京旅游的ppt"), eq(context), isNull()))
                .thenAnswer(invocation -> PlanTaskSession.builder()
                        .taskId(invocation.getArgument(1, String.class))
                        .planningPhase(PlanningPhaseEnum.ASK_USER)
                        .build());
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
                new CompletedArtifactIntentRecoveryService(resolver),
                null,
                conversationTaskStateService,
                matcher,
                null,
                null,
                arbiter
        );

        PlanTaskSession result = service.handlePlanRequest("1", context, null, null);

        assertThat(result.getTaskId()).isNotEqualTo("selector-task");
        verify(conversationTaskStateService).clearPendingFollowUpRecommendations("LARK_PRIVATE_CHAT:chat-1:chat-root");
        verify(graphRunner).run(any(), eq(result.getTaskId()), eq("生成一份北京旅游的ppt"), eq(context), isNull());
    }

    @Test
    void conflictChoiceTwoShowsCurrentTaskActionSelectionInsteadOfExecutingRecommendation() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSupervisorGraphRunner graphRunner = mock(PlannerSupervisorGraphRunner.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        FollowUpRecommendationExecutionService followUpRecommendationExecutionService = mock(FollowUpRecommendationExecutionService.class);
        PendingFollowUpConflictArbiter arbiter = new PendingFollowUpConflictArbiter(matcher);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PendingFollowUpRecommendation recommendation = recommendation();
        PlanTaskSession selector = PlanTaskSession.builder()
                .taskId("selector-task")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .intakeState(TaskIntakeState.builder()
                        .continuationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .pendingFollowUpConflictChoice(PendingFollowUpConflictChoice.builder()
                                .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                                .originalInstruction("生成一份北京旅游的ppt")
                                .newTaskInstruction("生成一份北京旅游的ppt")
                                .selectedRecommendationId("GENERATE_DOC_FROM_PPT")
                                .expiresAt(Instant.now().plusSeconds(60))
                                .build())
                        .pendingInteractionType(PendingInteractionTypeEnum.FOLLOW_UP_CONFLICT_CHOICE)
                        .build())
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .intakeState(TaskIntakeState.builder().build())
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("selector-task", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("selector-task")).thenReturn(selector);
        when(sessionService.get("task-1")).thenReturn(completed);
        when(resolver.resolveEditableArtifacts("task-1")).thenReturn(List.of(
                ArtifactRecord.builder().artifactId("ppt-1").taskId("task-1").type(ArtifactTypeEnum.PPT).title("9191项目启动会汇报").url("https://slides.example/9191").build()
        ));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
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
                new CompletedArtifactIntentRecoveryService(resolver),
                null,
                conversationTaskStateService,
                matcher,
                followUpRecommendationExecutionService,
                null,
                arbiter
        );

        PlanTaskSession result = service.handlePlanRequest("2", context, null, null);

        assertThat(result).isSameAs(completed);
        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        assertThat(result.getIntakeState().getAssistantReply())
                .contains("已切回这个已完成任务")
                .contains("推荐下一步")
                .contains("基于这份PPT补一份配套文档")
                .contains("回复编号即可执行对应推荐")
                .contains("https://slides.example/9191");
        assertThat(result.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.STATUS_QUERY);
        assertThat(result.getIntakeState().getReadOnlyView()).isEqualTo("COMPLETED_TASKS");
        verify(conversationTaskStateService).markPendingFollowUpAwaitingSelection("LARK_PRIVATE_CHAT:chat-1:chat-root", false);
        verify(conversationTaskStateService).syncFromSession(completed);
        verify(resolver).bindConversation(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        verifyNoInteractions(followUpRecommendationExecutionService);
    }

    private PendingFollowUpRecommendation recommendation() {
        return documentRecommendation();
    }

    private PendingFollowUpRecommendation documentRecommendation() {
        return PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_DOC_FROM_PPT")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.DOC)
                .suggestedUserInstruction("基于这份PPT补一份配套文档")
                .plannerInstruction("保留现有PPT，基于该PPT新增一份配套文档。")
                .build();
    }

    private PendingFollowUpRecommendation pptRecommendation() {
        return PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_PPT_FROM_DOC")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.PPT)
                .suggestedUserInstruction("基于这份文档生成一版汇报PPT")
                .plannerInstruction("保留现有文档，基于该文档新增一份汇报PPT。")
                .build();
    }

    private PendingFollowUpRecommendation summaryRecommendation() {
        return PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_SHAREABLE_SUMMARY")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.SUMMARY)
                .suggestedUserInstruction("基于当前任务内容生成一段可直接发送的摘要")
                .plannerInstruction("保留现有产物，新增一段可直接发送的任务摘要。")
                .build();
    }
}
