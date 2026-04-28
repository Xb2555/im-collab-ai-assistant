package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannerConversationServiceTests {

    @Test
    void shouldRouteClarificationReplyToResume() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        SupervisorPlannerService supervisorPlannerService = mock(SupervisorPlannerService.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                supervisorPlannerService
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder().chatId("chat-1").build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(intakeService.decide(session, "补充范围", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.CLARIFICATION_REPLY, "补充范围"));
        when(supervisorPlannerService.resume("task-1", "补充范围", false)).thenReturn(session);

        PlanTaskSession result = service.handlePlanRequest("补充范围", workspaceContext, null, null);

        assertThat(result).isSameAs(session);
        verify(supervisorPlannerService).resume("task-1", "补充范围", false);
    }

    @Test
    void shouldReturnCurrentSessionForStatusQuery() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        SupervisorPlannerService supervisorPlannerService = mock(SupervisorPlannerService.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                supervisorPlannerService
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder().chatId("chat-1").build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(intakeService.decide(session, "状态", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.STATUS_QUERY, "状态"));
        when(sessionService.get("task-1")).thenReturn(session);

        PlanTaskSession result = service.handlePlanRequest("状态", workspaceContext, null, null);

        assertThat(result).isSameAs(session);
        verify(sessionService).get("task-1");
    }

    @Test
    void shouldRouteNewTaskToPlan() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        SupervisorPlannerService supervisorPlannerService = mock(SupervisorPlannerService.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                supervisorPlannerService
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder().chatId("chat-1").build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", false, "LARK:chat-1"));
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(intakeService.decide(session, "生成周报", null, false))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.NEW_TASK, "生成周报"));
        when(supervisorPlannerService.plan("生成周报", workspaceContext, "task-1", null)).thenReturn(session);

        PlanTaskSession result = service.handlePlanRequest("生成周报", workspaceContext, null, null);

        assertThat(result).isSameAs(session);
        verify(supervisorPlannerService).plan("生成周报", workspaceContext, "task-1", null);
    }

    @Test
    void shouldRoutePlanAdjustmentToAdjustPlan() {
        TaskSessionResolver resolver = mock(TaskSessionResolver.class);
        TaskIntakeService intakeService = mock(TaskIntakeService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        SupervisorPlannerService supervisorPlannerService = mock(SupervisorPlannerService.class);
        PlannerConversationService service = new PlannerConversationService(
                resolver,
                intakeService,
                sessionService,
                supervisorPlannerService
        );

        WorkspaceContext workspaceContext = WorkspaceContext.builder().chatId("chat-1").build();
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        when(resolver.resolve(null, workspaceContext)).thenReturn(new TaskSessionResolution("task-1", true, "LARK:chat-1"));
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(intakeService.decide(session, "成功标准再加一条", null, true))
                .thenReturn(new TaskIntakeDecision(TaskIntakeTypeEnum.PLAN_ADJUSTMENT, "成功标准再加一条"));
        when(supervisorPlannerService.adjustPlan("task-1", "成功标准再加一条", workspaceContext)).thenReturn(session);

        PlanTaskSession result = service.handlePlanRequest("成功标准再加一条", workspaceContext, null, null);

        assertThat(result).isSameAs(session);
        verify(supervisorPlannerService).adjustPlan("task-1", "成功标准再加一条", workspaceContext);
    }
}
