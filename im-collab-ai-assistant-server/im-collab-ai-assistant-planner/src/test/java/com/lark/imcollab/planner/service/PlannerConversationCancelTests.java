package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlannerConversationCancelTests {

    @Test
    void shouldRouteCancelTaskToAbortSession() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        SupervisorPlannerService supervisorPlannerService = mock(SupervisorPlannerService.class);
        TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                supervisorPlannerService,
                taskBridgeService
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder().chatId("chat-1").build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();
        PlanTaskSession abortedSession = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.ABORTED)
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(intakeService.decide(session, "\u53d6\u6d88\u4efb\u52a1", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.CANCEL_TASK, "\u53d6\u6d88\u4efb\u52a1"));
        when(sessionService.get("task-1")).thenReturn(abortedSession);

        PlanTaskSession result = service.handlePlanRequest("\u53d6\u6d88\u4efb\u52a1", workspaceContext, null, null);

        assertThat(result).isSameAs(abortedSession);
        verify(sessionService).markAborted("task-1", "User cancelled from conversation: \u53d6\u6d88\u4efb\u52a1");
        verify(taskBridgeService).ensureTask(abortedSession);
        verifyNoInteractions(supervisorPlannerService);
    }
}
