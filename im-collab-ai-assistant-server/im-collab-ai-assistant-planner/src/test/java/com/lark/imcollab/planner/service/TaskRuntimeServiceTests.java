package com.lark.imcollab.planner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRuntimeServiceTests {

    @Test
    void shouldProjectPlanReadyToRuntimeTaskStepsAndEvent() {
        PlannerStateStore store = mock(PlannerStateStore.class);
        when(store.findTask("task-1")).thenReturn(Optional.empty());
        when(store.findArtifactsByTaskId("task-1")).thenReturn(List.of());
        when(store.findStepsByTaskId("task-1")).thenReturn(List.of());
        TaskRuntimeService service = new TaskRuntimeService(store, new PlanGraphBuilder(), new ObjectMapper());

        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .version(3)
                .planBlueprint(PlanBlueprint.builder()
                        .taskBrief("Prepare weekly boss update")
                        .risks(List.of("Missing progress data"))
                        .planCards(List.of(UserPlanCard.builder()
                                .cardId("step-1")
                                .title("Draft report")
                                .type(PlanCardTypeEnum.DOC)
                                .build()))
                        .build())
                .build();

        service.projectPlanReady(session, TaskEventTypeEnum.PLAN_READY);

        var taskCaptor = forClass(TaskRecord.class);
        var stepCaptor = forClass(TaskStepRecord.class);
        var eventCaptor = forClass(TaskEventRecord.class);
        verify(store).saveTask(taskCaptor.capture());
        verify(store).saveStep(stepCaptor.capture());
        verify(store).appendRuntimeEvent(eventCaptor.capture());

        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(TaskStatusEnum.WAITING_APPROVAL);
        assertThat(taskCaptor.getValue().getGoal()).isEqualTo("Prepare weekly boss update");
        assertThat(taskCaptor.getValue().getRiskFlags()).containsExactly("Missing progress data");
        assertThat(stepCaptor.getValue().getStepId()).isEqualTo("step-1");
        assertThat(eventCaptor.getValue().getType()).isEqualTo(TaskEventTypeEnum.PLAN_READY);
    }

    @Test
    void shouldMarkRemovedRuntimeStepsAsSuperseded() {
        PlannerStateStore store = mock(PlannerStateStore.class);
        when(store.findTask("task-1")).thenReturn(Optional.empty());
        when(store.findArtifactsByTaskId("task-1")).thenReturn(List.of());
        when(store.findStepsByTaskId("task-1")).thenReturn(List.of(TaskStepRecord.builder()
                .stepId("old-step")
                .taskId("task-1")
                .status(StepStatusEnum.READY)
                .version(1)
                .build()));
        TaskRuntimeService service = new TaskRuntimeService(store, new PlanGraphBuilder(), new ObjectMapper());

        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .planBlueprint(PlanBlueprint.builder()
                        .taskBrief("Updated plan")
                        .planCards(List.of(UserPlanCard.builder()
                                .cardId("new-step")
                                .title("New step")
                                .type(PlanCardTypeEnum.DOC)
                                .build()))
                        .build())
                .build();

        service.projectPlanReady(session, TaskEventTypeEnum.PLAN_ADJUSTED);

        var stepCaptor = forClass(TaskStepRecord.class);
        verify(store, org.mockito.Mockito.times(2)).saveStep(stepCaptor.capture());
        assertThat(stepCaptor.getAllValues())
                .anySatisfy(step -> {
                    assertThat(step.getStepId()).isEqualTo("old-step");
                    assertThat(step.getStatus()).isEqualTo(StepStatusEnum.SUPERSEDED);
                    assertThat(step.getVersion()).isEqualTo(2);
                });
    }
}
