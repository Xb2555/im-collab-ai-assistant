package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.planner.gate.PlannerCapabilityPolicy;
import com.lark.imcollab.planner.planning.TaskPlanningService;
import com.lark.imcollab.planner.runtime.TaskRuntimeProjectionService;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewGateNodeServiceTest {

    @Test
    void emptyPlanReviewFailsTaskInsteadOfAskingUser() {
        PlannerReviewTool reviewTool = new PlannerReviewTool(new PlannerCapabilityPolicy());
        PlannerGateTool gateTool = mock(PlannerGateTool.class);
        PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
        TaskPlanningService taskPlanningService = mock(TaskPlanningService.class);
        TaskRuntimeProjectionService projectionService = mock(TaskRuntimeProjectionService.class);
        TaskRuntimeService taskRuntimeService = mock(TaskRuntimeService.class);
        PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
        PlannerSessionService sessionService = mock(PlannerSessionService.class);
        ReviewGateNodeService service = new ReviewGateNodeService(
                reviewTool,
                gateTool,
                questionTool,
                taskPlanningService,
                projectionService,
                taskRuntimeService,
                memoryService,
                sessionService
        );
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.INTENT_READY)
                .build();
        when(sessionService.get("task-1")).thenReturn(session);

        PlanReviewResult result = service.review("task-1");
        PlanTaskSession gated = service.gateAndProject("task-1", TaskEventTypeEnum.PLAN_READY);

        assertThat(result.passed()).isFalse();
        assertThat(session.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.FAILED);
        assertThat(session.getTransitionReason()).isEqualTo("未能生成可执行的计划步骤。");
        assertThat(gated).isSameAs(session);
        verify(questionTool, never()).askUser(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(sessionService).save(session);
        verify(projectionService).projectStage(session, TaskEventTypeEnum.PLAN_FAILED, "未能生成可执行的计划步骤。");
        verify(sessionService).publishEvent("task-1", "FAILED");
        verify(taskPlanningService, never()).buildReadyPlan(session);
    }
}
