package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
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
}
