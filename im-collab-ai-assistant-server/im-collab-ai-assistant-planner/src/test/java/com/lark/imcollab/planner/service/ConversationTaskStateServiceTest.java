package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationTaskStateServiceTest {

    private final PlannerStateStore stateStore = mock(PlannerStateStore.class);
    private final ConversationTaskStateService service = new ConversationTaskStateService(stateStore);

    @Test
    void planReadyForDifferentTaskClearsPendingFollowUpRecommendations() {
        ConversationTaskState existing = ConversationTaskState.builder()
                .conversationKey("LARK:chat-1")
                .activeTaskId("task-old")
                .lastCompletedTaskId("task-completed")
                .pendingFollowUpRecommendations(List.of(PendingFollowUpRecommendation.builder()
                        .recommendationId("rec-ppt")
                        .targetTaskId("task-completed")
                        .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                        .sourceArtifactType(ArtifactTypeEnum.DOC)
                        .build()))
                .pendingFollowUpAwaitingSelection(true)
                .build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-new")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .inputContext(TaskInputContext.builder()
                        .chatId("chat-1")
                        .build())
                .intakeState(TaskIntakeState.builder()
                        .continuationKey("LARK:chat-1")
                        .build())
                .build();

        when(stateStore.findConversationTaskState("LARK:chat-1")).thenReturn(Optional.of(existing));

        service.syncFromSession(session);

        assertThat(existing.getPendingFollowUpRecommendations()).isEmpty();
        assertThat(existing.isPendingFollowUpAwaitingSelection()).isFalse();
        assertThat(existing.getActiveTaskId()).isEqualTo("task-new");
        verify(stateStore).saveConversationTaskState(any(ConversationTaskState.class));
    }

    @Test
    void planReadyForSameCompletedTaskAlsoClearsPendingFollowUpRecommendations() {
        ConversationTaskState existing = ConversationTaskState.builder()
                .conversationKey("LARK:chat-1")
                .activeTaskId("task-completed")
                .lastCompletedTaskId("task-completed")
                .pendingFollowUpRecommendations(List.of(PendingFollowUpRecommendation.builder()
                        .recommendationId("rec-summary")
                        .targetTaskId("task-completed")
                        .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                        .sourceArtifactType(ArtifactTypeEnum.DOC)
                        .build()))
                .pendingFollowUpAwaitingSelection(true)
                .build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-completed")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .inputContext(TaskInputContext.builder()
                        .chatId("chat-1")
                        .build())
                .intakeState(TaskIntakeState.builder()
                        .continuationKey("LARK:chat-1")
                        .build())
                .build();

        when(stateStore.findConversationTaskState("LARK:chat-1")).thenReturn(Optional.of(existing));

        service.syncFromSession(session);

        assertThat(existing.getPendingFollowUpRecommendations()).isEmpty();
        assertThat(existing.isPendingFollowUpAwaitingSelection()).isFalse();
        assertThat(existing.getActiveTaskId()).isEqualTo("task-completed");
        verify(stateStore).saveConversationTaskState(any(ConversationTaskState.class));
    }
}
