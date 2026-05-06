package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskSessionResolverTest {

    @Test
    void explicitTaskIdIsExistingOnlyWhenSessionExists() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        when(stateStore.findSession("task-new")).thenReturn(Optional.empty());
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        TaskSessionResolution resolution = resolver.resolve("task-new", WorkspaceContext.builder()
                .chatId("chat-1")
                .build());

        assertThat(resolution.taskId()).isEqualTo("task-new");
        assertThat(resolution.existingSession()).isFalse();
    }

    @Test
    void explicitTaskIdKeepsExistingSessionWhenPresent() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        when(stateStore.findSession("task-existing")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-existing")
                .build()));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        TaskSessionResolution resolution = resolver.resolve("task-existing", null);

        assertThat(resolution.taskId()).isEqualTo("task-existing");
        assertThat(resolution.existingSession()).isTrue();
    }

    @Test
    void conversationBindingKeepsCompletedSessionForReadOnlyFollowUp() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .build();
        when(stateStore.findConversationTaskId("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of("task-completed"));
        when(stateStore.findSession("task-completed")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-completed")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build()));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        TaskSessionResolution resolution = resolver.resolve(null, context);

        assertThat(resolution.taskId()).isEqualTo("task-completed");
        assertThat(resolution.existingSession()).isTrue();
        assertThat(resolution.continuationKey()).isEqualTo("LARK_PRIVATE_CHAT:chat-1:chat-root");
    }

    @Test
    void executingTaskInConversationStateWinsOverLegacyBinding() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .build();
        when(stateStore.findConversationTaskState("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of(ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-active")
                        .executingTaskId("task-running")
                        .lastCompletedTaskId("task-done")
                        .build()));
        when(stateStore.findSession("task-running")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-running")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build()));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        TaskSessionResolution resolution = resolver.resolve(null, context);

        assertThat(resolution.taskId()).isEqualTo("task-running");
        assertThat(resolution.existingSession()).isTrue();
    }

    @Test
    void activeTaskInConversationStateWinsWhenNoExecutingTaskExists() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_PRIVATE_CHAT")
                .chatId("chat-1")
                .build();
        when(stateStore.findConversationTaskState("LARK_PRIVATE_CHAT:chat-1:chat-root"))
                .thenReturn(Optional.of(ConversationTaskState.builder()
                        .conversationKey("LARK_PRIVATE_CHAT:chat-1:chat-root")
                        .activeTaskId("task-active")
                        .build()));
        when(stateStore.findSession("task-active")).thenReturn(Optional.of(PlanTaskSession.builder()
                .taskId("task-active")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build()));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        TaskSessionResolution resolution = resolver.resolve(null, context);

        assertThat(resolution.taskId()).isEqualTo("task-active");
        assertThat(resolution.existingSession()).isTrue();
    }

    @Test
    void completedCandidatesAreScopedToCurrentConversationAndOwner() {
        PlannerStateStore stateStore = mock(PlannerStateStore.class);
        WorkspaceContext context = WorkspaceContext.builder()
                .inputSource("LARK_GROUP_CHAT")
                .chatId("chat-1")
                .threadId("thread-1")
                .senderOpenId("ou-1")
                .build();
        when(stateStore.findTasksByConversation(
                "LARK_GROUP_CHAT",
                "chat-1",
                "thread-1",
                "ou-1",
                List.of(TaskStatusEnum.COMPLETED),
                5
        )).thenReturn(List.of(TaskRecord.builder()
                .taskId("task-1")
                .title("采购评审 PPT")
                .updatedAt(Instant.parse("2026-05-04T08:00:00Z"))
                .build()));
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(ArtifactRecord.builder()
                .artifactId("artifact-1")
                .type(ArtifactTypeEnum.PPT)
                .build()));
        TaskSessionResolver resolver = new TaskSessionResolver(stateStore);

        var candidates = resolver.resolveCompletedCandidates(context);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getTaskId()).isEqualTo("task-1");
        assertThat(candidates.get(0).getArtifactTypes()).containsExactly(ArtifactTypeEnum.PPT);
    }

}
