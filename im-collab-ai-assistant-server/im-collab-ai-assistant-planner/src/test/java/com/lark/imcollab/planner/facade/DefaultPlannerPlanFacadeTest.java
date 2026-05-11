package com.lark.imcollab.planner.facade;

import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.planner.intent.IntentRoutingResult;
import com.lark.imcollab.planner.intent.LlmIntentClassifier;
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

        when(matcher.match("帮我生成一份飞书文档，标题必须包含 IMMEDIATE_PREVIEW_FIX。", List.of(recommendation), false, true))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.none());

        String reply = facade.previewImmediateReply(
                "帮我生成一份飞书文档，标题必须包含 IMMEDIATE_PREVIEW_FIX。",
                context,
                null,
                null
        );

        assertThat(reply).isEqualTo("🧭 需求我接住了，我先理一下重点，稍后给你一个可执行的计划。");
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
        when(matcher.match("基于当前任务内容生成一段可直接发送的摘要", List.of(recommendation), false, true))
                .thenReturn(PendingFollowUpRecommendationMatcher.MatchResult.selected(recommendation));

        String reply = facade.previewImmediateReply(
                "基于当前任务内容生成一段可直接发送的摘要",
                context,
                null,
                null
        );

        assertThat(reply).isEqualTo("🔄 这个后续动作我接住了，我会先把当前任务扩展一下，再把更新后的计划回给你。");
        verify(matcher).match("基于当前任务内容生成一段可直接发送的摘要", List.of(recommendation), false, true);
    }
}
