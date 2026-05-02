package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

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
}
