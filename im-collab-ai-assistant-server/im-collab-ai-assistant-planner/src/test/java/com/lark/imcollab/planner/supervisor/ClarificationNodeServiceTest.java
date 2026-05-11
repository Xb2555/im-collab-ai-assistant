package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PendingInteractionTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.clarification.ClarificationService;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClarificationNodeServiceTest {

    private final PlannerSessionService sessionService = mock(PlannerSessionService.class);
    private final ClarificationService clarificationService = mock(ClarificationService.class);
    private final PlannerConversationMemoryService memoryService = mock(PlannerConversationMemoryService.class);
    private final PlanningNodeService planningNodeService = mock(PlanningNodeService.class);
    private final ReplanNodeService replanNodeService = mock(ReplanNodeService.class);
    private final TaskBridgeService taskBridgeService = mock(TaskBridgeService.class);
    private final TaskRuntimeService taskRuntimeService = mock(TaskRuntimeService.class);
    private final PlannerExecutionTool executionTool = mock(PlannerExecutionTool.class);
    private final ClarificationNodeService service = new ClarificationNodeService(
            sessionService,
            clarificationService,
            memoryService,
            planningNodeService,
            replanNodeService,
            taskBridgeService,
            taskRuntimeService,
            executionTool
    );

    @Test
    void resumeForExecutingPlanAdjustmentRoutesBackToReplan() {
        PlanTaskSession askUser = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .intakeState(TaskIntakeState.builder()
                        .pendingInteractionType(PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT)
                        .build())
                .build();
        PlanTaskSession ready = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .intakeState(TaskIntakeState.builder()
                        .pendingInteractionType(PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT)
                        .pendingAdjustmentInstruction("旧调整")
                        .build())
                .build();
        WorkspaceContext workspaceContext = WorkspaceContext.builder().inputSource("LARK_PRIVATE_CHAT").build();
        when(sessionService.get("task-1")).thenReturn(askUser);
        when(replanNodeService.replan("task-1", "ppt要5页，最后一页内容是关于lfy66的", workspaceContext))
                .thenReturn(ready);

        PlanTaskSession result = service.resume("task-1", "ppt要5页，最后一页内容是关于lfy66的", workspaceContext);

        assertThat(result).isSameAs(ready);
        assertThat(askUser.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.REPLANNING);
        assertThat(ready.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        assertThat(ready.getIntakeState().getPendingInteractionType()).isNull();
        assertThat(ready.getIntakeState().getPendingAdjustmentInstruction()).isNull();
        verify(replanNodeService).replan("task-1", "ppt要5页，最后一页内容是关于lfy66的", workspaceContext);
        verify(planningNodeService, never()).plan("task-1", "ppt要5页，最后一页内容是关于lfy66的", workspaceContext, "ppt要5页，最后一页内容是关于lfy66的");
        verify(taskBridgeService).ensureTask(ready);
        verify(taskRuntimeService).reconcilePlanReadyProjection(ready, TaskEventTypeEnum.PLAN_ADJUSTED);
    }

    @Test
    void resumeForExecutingPlanAdjustmentCanRestoreOriginalExecution() {
        PlanTaskSession askUser = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .intakeState(TaskIntakeState.builder()
                        .pendingInteractionType(PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT)
                        .resumeOriginalExecutionAvailable(true)
                        .assistantReply("您是想暂停当前任务的执行流程，还是想修改计划内容？")
                        .build())
                .build();
        WorkspaceContext workspaceContext = WorkspaceContext.builder().inputSource("LARK_PRIVATE_CHAT").build();
        when(sessionService.get("task-1")).thenReturn(askUser);
        when(executionTool.confirmExecution("task-1"))
                .thenReturn(PlannerToolResult.success("task-1", PlanningPhaseEnum.EXECUTING, "execution confirmed", null));

        PlanTaskSession result = service.resume("task-1", "恢复执行", workspaceContext);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.EXECUTING);
        assertThat(result.getIntakeState().getIntakeType()).isEqualTo(TaskIntakeTypeEnum.CONFIRM_ACTION);
        assertThat(result.getIntakeState().getPendingInteractionType()).isNull();
        assertThat(result.getIntakeState().isResumeOriginalExecutionAvailable()).isFalse();
        assertThat(result.getIntakeState().getAssistantReply()).contains("继续按原执行流程推进");
        verify(executionTool).confirmExecution("task-1");
        verify(replanNodeService, never()).replan("task-1", "恢复执行", workspaceContext);
        verify(planningNodeService, never()).plan("task-1", "恢复执行", workspaceContext, "恢复执行");
    }
}
