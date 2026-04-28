package com.lark.imcollab.app.planner.controller;

import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskStatus;
import com.lark.imcollab.common.domain.TaskType;
import com.lark.imcollab.common.facade.HarnessFacade;
import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.dto.PlanCommandRequest;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.SupervisorPlannerService;
import com.lark.imcollab.planner.service.TaskResultEvaluationService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlannerControllerCommandTest {

    @Mock private PlannerPlanFacade plannerPlanFacade;
    @Mock private SupervisorPlannerService supervisorPlannerService;
    @Mock private PlannerSessionService sessionService;
    @Mock private TaskRuntimeService taskRuntimeService;
    @Mock private TaskResultEvaluationService evaluationService;
    @Mock private PlannerStateStore repository;
    @Mock private HarnessFacade harnessFacade;

    @InjectMocks
    private PlannerController controller;

    @Test
    void confirmExecute_triggersHarness() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setVersion(1);

        when(sessionService.get("task-1")).thenReturn(session);
        when(harnessFacade.startExecution("task-1")).thenReturn(Task.builder()
                .taskId("task-1").type(TaskType.WRITE_DOC).status(TaskStatus.EXECUTING)
                .steps(new ArrayList<>()).artifacts(new ArrayList<>())
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("CONFIRM_EXECUTE");
        request.setVersion(1);

        controller.command("task-1", request);

        verify(harnessFacade).startExecution("task-1");
    }

    @Test
    void nonConfirmAction_doesNotTriggerHarness() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setVersion(1);

        when(sessionService.get("task-1")).thenReturn(session);

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("REPLAN");
        request.setVersion(1);
        request.setFeedback("change it");

        controller.command("task-1", request);

        verifyNoInteractions(harnessFacade);
    }
}
