package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskStatus;
import com.lark.imcollab.common.domain.TaskType;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskBridgeServiceTest {

    @Mock private TaskRepository taskRepository;
    @Mock private TaskEventRepository eventRepository;

    @InjectMocks
    private TaskBridgeService taskBridgeService;

    @Test
    void ensureTask_onlyWritesTask_doesNotStartHarness() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");

        Task existing = Task.builder()
                .taskId("task-1").type(TaskType.WRITE_DOC).status(TaskStatus.PLAN_READY)
                .steps(new ArrayList<>()).artifacts(new ArrayList<>())
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(taskRepository.findById("task-1")).thenReturn(Optional.of(existing));

        Task result = taskBridgeService.ensureTask(session);

        assertThat(result.getTaskId()).isEqualTo("task-1");
        verify(taskRepository).findById("task-1");
        verifyNoMoreInteractions(taskRepository);
        verifyNoInteractions(eventRepository);
    }

    @Test
    void ensureTask_createsTask_whenNotFound_doesNotStartHarness() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-2");

        when(taskRepository.findById("task-2")).thenReturn(Optional.empty());

        Task result = taskBridgeService.ensureTask(session);

        assertThat(result.getTaskId()).isEqualTo("task-2");
        assertThat(result.getStatus()).isEqualTo(TaskStatus.PLAN_READY);
        verify(taskRepository).save(any());
        verify(eventRepository).save(any());
    }
}
