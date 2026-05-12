package com.lark.imcollab.planner.facade;

import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.common.facade.DocumentEditIntentFacade;
import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.planner.intent.IntentRoutingResult;
import com.lark.imcollab.planner.intent.LlmIntentClassifier;
import com.lark.imcollab.planner.service.CompletedArtifactIntentRecoveryService;
import com.lark.imcollab.planner.service.ConversationTaskStateService;
import com.lark.imcollab.planner.service.PendingFollowUpRecommendationMatcher;
import com.lark.imcollab.planner.service.PlannerConversationService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskSessionResolution;
import com.lark.imcollab.planner.service.TaskSessionResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultPlannerPlanFacadeTest {

    @Test
    void startTaskImmediateReplyDoesNotPreviewPendingFollowUpRecommendation() {
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        DefaultPlannerPlanFacade facade = new DefaultPlannerPlanFacade(
                conversationService,
                resolver,
                sessionService,
                classifier,
                conversationTaskStateService,
                matcher
        );

        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation recommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_SUMMARY")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.SUMMARY)
                .suggestedUserInstruction("基于当前任务内容生成一段可直接发送的摘要")
                .plannerInstruction("保留现有产物，新增一段可直接发送的任务摘要。")
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(classifier.classify(completed, "帮我生成一份飞书文档，标题必须包含 IMMEDIATE_PREVIEW_FIX。", true))
                .thenReturn(Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.START_TASK,
                        0.95d,
                        "standalone new task",
                        "帮我生成一份飞书文档，标题必须包含 IMMEDIATE_PREVIEW_FIX。",
                        false
                )));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(List.of(recommendation))
                        .build()
        ));

        when(matcher.classifyCarryForwardCandidate(
                "帮我生成一份飞书文档，标题必须包含 IMMEDIATE_PREVIEW_FIX。",
                List.of(recommendation)
        )).thenReturn(PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED);
        when(matcher.match("帮我生成一份飞书文档，标题必须包含 IMMEDIATE_PREVIEW_FIX。", List.of(recommendation), false, true))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.none());

        String reply = facade.previewImmediateReply(
                "帮我生成一份飞书文档，标题必须包含 IMMEDIATE_PREVIEW_FIX。",
                context,
                null,
                null
        );

        assertThat(reply).isEqualTo("🧭 需求我接住了，我先理一下重点，稍后给你一个可执行的计划。");
        verify(matcher).classifyCarryForwardCandidate("帮我生成一份飞书文档，标题必须包含 IMMEDIATE_PREVIEW_FIX。", List.of(recommendation));
        verify(matcher).match("帮我生成一份飞书文档，标题必须包含 IMMEDIATE_PREVIEW_FIX。", List.of(recommendation), false, true);
    }

    @Test
    void adjustPlanImmediateReplyCanPreviewPendingFollowUpRecommendation() {
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        DefaultPlannerPlanFacade facade = new DefaultPlannerPlanFacade(
                conversationService,
                resolver,
                sessionService,
                classifier,
                conversationTaskStateService,
                matcher
        );

        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation recommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_PPT")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.PPT)
                .suggestedUserInstruction("基于这份文档生成一版汇报PPT")
                .plannerInstruction("保留现有文档，基于该文档新增一份汇报PPT初稿。")
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(classifier.classify(completed, "基于这份文档生成一版汇报PPT", true))
                .thenReturn(Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.ADJUST_PLAN,
                        0.95d,
                        "follow-up recommendation",
                        "基于这份文档生成一版汇报PPT",
                        false
                )));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(List.of(recommendation))
                        .build()
        ));
        when(matcher.match("基于这份文档生成一版汇报PPT", List.of(recommendation), false, false))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.selected(recommendation));

        String reply = facade.previewImmediateReply(
                "基于这份文档生成一版汇报PPT",
                context,
                null,
                null
        );

        assertThat(reply).isEqualTo("🔄 这个后续动作我接住了，我会先把当前任务扩展一下，再把更新后的计划回给你。");
        verify(matcher).match("基于这份文档生成一版汇报PPT", List.of(recommendation), false, false);
    }

    @Test
    void startTaskImmediateReplyStillPreviewsWhenRecommendationMatcherSelects() {
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        DefaultPlannerPlanFacade facade = new DefaultPlannerPlanFacade(
                conversationService,
                resolver,
                sessionService,
                classifier,
                conversationTaskStateService,
                matcher
        );

        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation recommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_SUMMARY")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.SUMMARY)
                .suggestedUserInstruction("基于当前任务内容生成一段可直接发送的摘要")
                .plannerInstruction("保留现有产物，新增一段可直接发送的任务摘要。")
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(classifier.classify(completed, "基于当前任务内容生成一段可直接发送的摘要", true))
                .thenReturn(Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.START_TASK,
                        0.88d,
                        "llm classified as standalone task",
                        "基于当前任务内容生成一段可直接发送的摘要",
                        false
                )));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(List.of(recommendation))
                        .build()
        ));
        when(matcher.classifyCarryForwardCandidate("基于当前任务内容生成一段可直接发送的摘要", List.of(recommendation)))
                .thenReturn(PendingFollowUpRecommendationMatcher.CarryForwardHint.EXACT_OR_PREFIX_MATCH);
        when(matcher.match("基于当前任务内容生成一段可直接发送的摘要", List.of(recommendation), false, true))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.selected(recommendation));

        String reply = facade.previewImmediateReply(
                "基于当前任务内容生成一段可直接发送的摘要",
                context,
                null,
                null
        );

        assertThat(reply).isEqualTo("🔄 这个后续动作我接住了，我会先把当前任务扩展一下，再把更新后的计划回给你。");
        verify(matcher).classifyCarryForwardCandidate("基于当前任务内容生成一段可直接发送的摘要", List.of(recommendation));
        verify(matcher).match("基于当前任务内容生成一段可直接发送的摘要", List.of(recommendation), false, true);
    }

    @Test
    void startTaskImmediateReplyStillPreviewsWhenRecommendationIsOnlySemanticMatch() {
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        DefaultPlannerPlanFacade facade = new DefaultPlannerPlanFacade(
                conversationService,
                resolver,
                sessionService,
                classifier,
                conversationTaskStateService,
                matcher
        );

        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation recommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_PPT")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.PPT)
                .suggestedUserInstruction("基于这份文档生成一版汇报PPT")
                .plannerInstruction("保留现有文档，基于该文档新增一份汇报PPT初稿。")
                .build();
        String input = "帮我再根据当前文档生成汇报ppt，要求4页，每页60字";
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(classifier.classify(completed, input, true))
                .thenReturn(Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.START_TASK,
                        0.88d,
                        "llm classified as standalone task",
                        input,
                        false
                )));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(List.of(recommendation))
                        .build()
        ));
        when(matcher.classifyCarryForwardCandidate(input, List.of(recommendation)))
                .thenReturn(PendingFollowUpRecommendationMatcher.CarryForwardHint.SEMANTIC_MATCH_WORTH_LLM);
        when(matcher.match(input, List.of(recommendation), false, true))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.selected(recommendation));

        String reply = facade.previewImmediateReply(input, context, null, null);

        assertThat(reply).contains("这个后续动作我接住了");
        verify(matcher).classifyCarryForwardCandidate(input, List.of(recommendation));
        verify(matcher).match(input, List.of(recommendation), false, true);
    }

    @Test
    void startTaskImmediateReplyStillPreviewsWhenSingleSemanticMatchExistsAmongMultipleRecommendations() {
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        DefaultPlannerPlanFacade facade = new DefaultPlannerPlanFacade(
                conversationService,
                resolver,
                sessionService,
                classifier,
                conversationTaskStateService,
                matcher
        );

        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation pptRecommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_PPT")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.PPT)
                .suggestedUserInstruction("基于这份文档生成一版汇报PPT")
                .plannerInstruction("保留现有文档，基于该文档新增一份汇报PPT初稿。")
                .build();
        PendingFollowUpRecommendation summaryRecommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_SUMMARY")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.SUMMARY)
                .suggestedUserInstruction("基于当前任务内容生成一段可直接发送的摘要")
                .plannerInstruction("保留现有产物，新增一段可直接发送的任务摘要。")
                .build();
        List<PendingFollowUpRecommendation> recommendations = List.of(pptRecommendation, summaryRecommendation);
        String input = "帮我根据这个文档生成一个ppt，要求能够概括文档重点，汇报给老板的";
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(classifier.classify(completed, input, true))
                .thenReturn(Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.START_TASK,
                        0.88d,
                        "llm classified as standalone task",
                        input,
                        false
                )));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(recommendations)
                        .build()
        ));
        when(matcher.classifyCarryForwardCandidate(input, recommendations))
                .thenReturn(PendingFollowUpRecommendationMatcher.CarryForwardHint.SEMANTIC_MATCH_WORTH_LLM);
        when(matcher.match(input, recommendations, false, true))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.selected(pptRecommendation));

        String reply = facade.previewImmediateReply(input, context, null, null);

        assertThat(reply).isEqualTo("🔄 这个后续动作我接住了，我会先把当前任务扩展一下，再把更新后的计划回给你。");
        verify(matcher).classifyCarryForwardCandidate(input, recommendations);
        verify(matcher).match(input, recommendations, false, true);
    }

    @Test
    void previewSuppressesImmediateReplyWhenExecutionMustAskNewOrCurrentChoice() {
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        DefaultPlannerPlanFacade facade = new DefaultPlannerPlanFacade(
                conversationService,
                resolver,
                sessionService,
                classifier,
                conversationTaskStateService,
                matcher
        );

        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation pptRecommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_PPT")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.PPT)
                .suggestedUserInstruction("基于这份文档生成一版汇报PPT")
                .plannerInstruction("保留现有文档，基于该文档新增一份汇报PPT初稿。")
                .build();
        PendingFollowUpRecommendation summaryRecommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_SUMMARY")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.SUMMARY)
                .suggestedUserInstruction("基于当前任务内容生成一段可直接发送的摘要")
                .plannerInstruction("保留现有产物，新增一段可直接发送的任务摘要。")
                .build();
        List<PendingFollowUpRecommendation> recommendations = List.of(pptRecommendation, summaryRecommendation);
        String input = "帮我整理一份材料";
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(classifier.classify(completed, input, true))
                .thenReturn(Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.START_TASK,
                        0.88d,
                        "llm classified as standalone task",
                        input,
                        false
                )));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(recommendations)
                        .build()
        ));
        when(matcher.classifyCarryForwardCandidate(input, recommendations))
                .thenReturn(PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED);
        when(matcher.match(input, recommendations, false, true))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.none());

        String reply = facade.previewImmediateReply(input, context, null, null);

        assertThat(reply).isEmpty();
        verify(matcher).classifyCarryForwardCandidate(input, recommendations);
        verify(matcher).match(input, recommendations, false, true);
    }

    @Test
    void previewSuppressesImmediateReplyWhenAdjustmentStillNeedsNewOrCurrentChoice() {
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        DefaultPlannerPlanFacade facade = new DefaultPlannerPlanFacade(
                conversationService,
                resolver,
                sessionService,
                classifier,
                conversationTaskStateService,
                matcher
        );

        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation summaryRecommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_SUMMARY")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.SUMMARY)
                .suggestedUserInstruction("基于当前任务内容生成一段可直接发送的摘要")
                .plannerInstruction("保留现有产物，新增一段可直接发送的任务摘要。")
                .build();
        List<PendingFollowUpRecommendation> recommendations = List.of(summaryRecommendation);
        String input = "整理一下材料";
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(classifier.classify(completed, input, true))
                .thenReturn(Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.ADJUST_PLAN,
                        0.88d,
                        "llm classified as adjustment",
                        input,
                        false,
                        null,
                        AdjustmentTargetEnum.COMPLETED_ARTIFACT
                )));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(recommendations)
                        .build()
        ));
        when(matcher.classifyCarryForwardCandidate(input, recommendations))
                .thenReturn(PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED);
        when(matcher.match(input, recommendations, false, false))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.none());

        String reply = facade.previewImmediateReply(input, context, null, null);

        assertThat(reply).isEmpty();
        verify(matcher).classifyCarryForwardCandidate(input, recommendations);
        verify(matcher).match(input, recommendations, false, false);
    }

    @Test
    void startTaskImmediateReplyStillPreviewsForImplicitCurrentTaskSummaryRecommendation() {
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        DefaultPlannerPlanFacade facade = new DefaultPlannerPlanFacade(
                conversationService,
                resolver,
                sessionService,
                classifier,
                conversationTaskStateService,
                matcher
        );

        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation recommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_SUMMARY")
                .targetTaskId("task-1")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.SUMMARY)
                .suggestedUserInstruction("基于当前任务内容生成一段可直接发送的摘要")
                .plannerInstruction("保留现有产物，新增一段可直接发送的任务摘要。")
                .build();
        String input = "帮我生成一下摘要，要能直接发在群里的";
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(classifier.classify(completed, input, true))
                .thenReturn(Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.START_TASK,
                        0.88d,
                        "llm classified as standalone task",
                        input,
                        false
                )));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(List.of(recommendation))
                        .build()
        ));
        when(matcher.classifyCarryForwardCandidate(input, List.of(recommendation)))
                .thenReturn(PendingFollowUpRecommendationMatcher.CarryForwardHint.SEMANTIC_MATCH_WORTH_LLM);
        when(matcher.match(input, List.of(recommendation), false, true))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.selected(recommendation));

        String reply = facade.previewImmediateReply(input, context, null, null);

        assertThat(reply).contains("这个后续动作我接住了");
        verify(matcher).classifyCarryForwardCandidate(input, List.of(recommendation));
        verify(matcher).match(input, List.of(recommendation), false, true);
    }

    @Test
    void previewImmediateReplyRecoversCompletedDocEditFromStartTask() {
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        DocumentEditIntentFacade documentEditIntentFacade = mock(DocumentEditIntentFacade.class);
        PresentationEditIntentFacade presentationEditIntentFacade = mock(PresentationEditIntentFacade.class);
        CompletedArtifactIntentRecoveryService recoveryService = new CompletedArtifactIntentRecoveryService(
                resolver,
                provider(documentEditIntentFacade),
                provider(presentationEditIntentFacade)
        );
        DefaultPlannerPlanFacade facade = new DefaultPlannerPlanFacade(
                conversationService,
                resolver,
                sessionService,
                classifier,
                conversationTaskStateService,
                matcher,
                recoveryService
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
        when(classifier.classify(completed, "加一小节关于ggbond的内容，随意编造即可", true))
                .thenReturn(Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.START_TASK,
                        0.92d,
                        "llm classified as standalone task",
                        "加一小节关于ggbond的内容，随意编造即可",
                        false
                )));
        when(resolver.conversationState(context)).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-doc")
                        .lastCompletedTaskId("task-doc")
                        .build()
        ));
        when(resolver.resolveEditableArtifacts("task-doc")).thenReturn(List.of(com.lark.imcollab.common.model.entity.ArtifactRecord.builder()
                .artifactId("artifact-doc-1")
                .taskId("task-doc")
                .type(ArtifactTypeEnum.DOC)
                .url("https://doc.example/1")
                .build()));
        when(resolver.isTaskCurrentInConversation("task-doc", context)).thenReturn(true);
        when(documentEditIntentFacade.resolve(eq("加一小节关于ggbond的内容，随意编造即可"), eq(context)))
                .thenReturn(DocumentEditIntent.builder().clarificationNeeded(false).build());
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.empty());

        String reply = facade.previewImmediateReply(
                "加一小节关于ggbond的内容，随意编造即可",
                context,
                null,
                null
        );

        assertThat(reply).isEqualTo("🔄 这条调整我收到了，我先顺着当前任务梳理一下，再把更新结果回给你。");
        verify(matcher, never()).match(anyString(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void previewImmediateReplyBypassesPendingFollowUpForCurrentCompletedPptEdit() {
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        DocumentEditIntentFacade documentEditIntentFacade = mock(DocumentEditIntentFacade.class);
        PresentationEditIntentFacade presentationEditIntentFacade = mock(PresentationEditIntentFacade.class);
        CompletedArtifactIntentRecoveryService recoveryService = new CompletedArtifactIntentRecoveryService(
                resolver,
                provider(documentEditIntentFacade),
                provider(presentationEditIntentFacade)
        );
        DefaultPlannerPlanFacade facade = new DefaultPlannerPlanFacade(
                conversationService,
                resolver,
                sessionService,
                classifier,
                conversationTaskStateService,
                matcher,
                recoveryService
        );

        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-ppt")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();
        PendingFollowUpRecommendation recommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_DOC_FROM_PPT")
                .targetTaskId("task-ppt")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.DOC)
                .suggestedUserInstruction("基于这份PPT补一份配套文档")
                .plannerInstruction("保留现有PPT，基于该PPT新增一份配套文档。")
                .build();
        String input = "改PPT，第二页的内容概览多加一小点";
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-ppt", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-ppt")).thenReturn(completed);
        when(classifier.classify(completed, input, true))
                .thenReturn(Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.ADJUST_PLAN,
                        0.95d,
                        "ppt edit",
                        input,
                        false
                )));
        when(resolver.isTaskCurrentInConversation("task-ppt", context)).thenReturn(true);
        when(resolver.resolveEditableArtifacts("task-ppt")).thenReturn(List.of(com.lark.imcollab.common.model.entity.ArtifactRecord.builder()
                .artifactId("artifact-ppt-1")
                .taskId("task-ppt")
                .type(ArtifactTypeEnum.PPT)
                .url("https://slides.example/1")
                .build()));
        when(presentationEditIntentFacade.resolve(eq(input), eq(context)))
                .thenReturn(com.lark.imcollab.common.model.entity.PresentationEditIntent.builder()
                        .clarificationNeeded(false)
                        .build());
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-ppt")
                        .lastCompletedTaskId("task-ppt")
                        .pendingFollowUpRecommendations(List.of(recommendation))
                        .build()
        ));

        String reply = facade.previewImmediateReply(input, context, null, null);

        assertThat(reply).isEqualTo("🔄 这条调整我收到了，我先顺着当前任务梳理一下，再把更新结果回给你。");
        verify(matcher, never()).classifyCarryForwardCandidate(anyString(), any());
        verify(matcher, never()).match(anyString(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void previewImmediateReplyDoesNotTreatObviousNewTaskAsFollowUp() {
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        DefaultPlannerPlanFacade facade = new DefaultPlannerPlanFacade(
                conversationService,
                resolver,
                sessionService,
                classifier,
                conversationTaskStateService,
                matcher,
                new CompletedArtifactIntentRecoveryService(resolver)
        );

        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
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
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-1", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-1")).thenReturn(completed);
        when(classifier.classify(completed, "帮我生成一份飞书文档，标题必须包含 9191，分2小节。", true))
                .thenReturn(Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.START_TASK,
                        0.95d,
                        "standalone new task",
                        "帮我生成一份飞书文档，标题必须包含 9191，分2小节。",
                        false
                )));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-1")
                        .lastCompletedTaskId("task-1")
                        .pendingFollowUpRecommendations(List.of(recommendation))
                        .build()
        ));

        String reply = facade.previewImmediateReply(
                "帮我生成一份飞书文档，标题必须包含 9191，分2小节。",
                context,
                null,
                null
        );

        assertThat(reply).isEqualTo("🧭 需求我接住了，我先理一下重点，稍后给你一个可执行的计划。");
        verify(matcher).match("帮我生成一份飞书文档，标题必须包含 9191，分2小节。", List.of(recommendation), false, true);
    }

    @Test
    void previewImmediateReplyTreatsBareSummaryAsCurrentTaskFollowUpWhenCompletedTaskIsExplicitlySelected() {
        PlannerConversationService conversationService = mock(PlannerConversationService.class);
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        LlmIntentClassifier classifier = mock(LlmIntentClassifier.class);
        ConversationTaskStateService conversationTaskStateService = mock(ConversationTaskStateService.class);
        PendingFollowUpRecommendationMatcher matcher = mock(PendingFollowUpRecommendationMatcher.class);
        DefaultPlannerPlanFacade facade = new DefaultPlannerPlanFacade(
                conversationService,
                resolver,
                sessionService,
                classifier,
                conversationTaskStateService,
                matcher,
                new CompletedArtifactIntentRecoveryService(resolver)
        );

        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .senderOpenId("ou-1")
                .build();
        PlanTaskSession completed = PlanTaskSession.builder()
                .taskId("task-ppt")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .intakeState(com.lark.imcollab.common.model.entity.TaskIntakeState.builder()
                        .readOnlyView("COMPLETED_TASKS")
                        .build())
                .build();
        PendingFollowUpRecommendation docRecommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_DOC")
                .targetTaskId("task-ppt")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.DOC)
                .sourceArtifactType(ArtifactTypeEnum.PPT)
                .plannerInstruction("保留现有 PPT，补一份配套文档。")
                .suggestedUserInstruction("基于这份PPT补一份配套文档")
                .build();
        PendingFollowUpRecommendation summaryRecommendation = PendingFollowUpRecommendation.builder()
                .recommendationId("GENERATE_SHAREABLE_SUMMARY")
                .targetTaskId("task-ppt")
                .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                .targetDeliverable(ArtifactTypeEnum.SUMMARY)
                .plannerInstruction("保留现有产物，新增一段可直接发送的任务摘要。")
                .suggestedUserInstruction("基于当前任务内容生成一段可直接发送的摘要")
                .build();
        when(resolver.resolve(null, context)).thenReturn(new TaskSessionResolution("task-ppt", true, "LARK_PRIVATE_CHAT:chat-1:chat-root"));
        when(sessionService.get("task-ppt")).thenReturn(completed);
        when(classifier.classify(completed, "帮我生成一份摘要", true))
                .thenReturn(Optional.of(new IntentRoutingResult(
                        TaskCommandTypeEnum.START_TASK,
                        0.92d,
                        "bare summary request",
                        "帮我生成一份摘要",
                        false
                )));
        when(conversationTaskStateService.find("LARK_PRIVATE_CHAT:chat-1:chat-root")).thenReturn(Optional.of(
                ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-ppt")
                        .lastCompletedTaskId("task-ppt")
                        .pendingFollowUpRecommendations(List.of(docRecommendation, summaryRecommendation))
                        .build()
        ));
        when(matcher.classifyCarryForwardCandidate("帮我生成一份摘要", List.of(docRecommendation, summaryRecommendation)))
                .thenReturn(PendingFollowUpRecommendationMatcher.CarryForwardHint.SEMANTIC_MATCH_WORTH_LLM);
        when(matcher.match("帮我生成一份摘要", List.of(docRecommendation, summaryRecommendation), false, true))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.selected(summaryRecommendation));

        String reply = facade.previewImmediateReply("帮我生成一份摘要", context, null, null);

        assertThat(reply).isEqualTo("🔄 这个后续动作我接住了，我会先把当前任务扩展一下，再把更新后的计划回给你。");
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T facade) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
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
}
