package com.lark.imcollab.app.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.facade.ImTaskCommandFacade;
import com.lark.imcollab.planner.service.PlannerRetryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorAction;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorDecision;
import com.lark.imcollab.planner.supervisor.PlannerSupervisorGraphRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlannerCommandApplicationServiceTest {

    @Mock private PlannerSupervisorGraphRunner graphRunner;
    @Mock private TaskBridgeService taskBridgeService;
    @Mock private PlannerRetryService plannerRetryService;
    @Mock private ImTaskCommandFacade taskCommandFacade;
    @Mock private TaskRuntimeService taskRuntimeService;
    @Mock private PlannerSessionService sessionService;

    private PlannerCommandApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PlannerCommandApplicationService(
                graphRunner,
                taskBridgeService,
                plannerRetryService,
                taskCommandFacade,
                taskRuntimeService,
                sessionService
        );
    }

    @Test
    void confirmExecutionUsesAsyncCommandFacadeWithoutRunningGraph() {
        PlanTaskSession executing = new PlanTaskSession();
        executing.setTaskId("task-1");
        executing.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
        when(taskCommandFacade.confirmExecution("task-1")).thenReturn(executing);

        PlanTaskSession result = service.confirmExecution("task-1", new PlanTaskSession());

        org.assertj.core.api.Assertions.assertThat(result).isSameAs(executing);
        verify(taskCommandFacade).confirmExecution("task-1");
        verify(graphRunner, never()).run(any(), eq("task-1"), any(), any(), any());
        verify(taskBridgeService, never()).ensureTask(any());
    }

    @Test
    void cancelFlipsStateAndProjectsRuntimeWithoutRunningGraph() {
        PlanTaskSession aborted = new PlanTaskSession();
        aborted.setTaskId("task-1");
        aborted.setPlanningPhase(PlanningPhaseEnum.ABORTED);
        when(sessionService.get("task-1")).thenReturn(aborted);

        PlanTaskSession result = service.cancel("task-1");

        org.assertj.core.api.Assertions.assertThat(result).isSameAs(aborted);
        InOrder inOrder = inOrder(taskCommandFacade, sessionService, taskRuntimeService);
        inOrder.verify(sessionService).markAborted("task-1", "User cancelled from GUI command");
        inOrder.verify(taskCommandFacade).cancelExecution("task-1");
        inOrder.verify(taskRuntimeService).projectPhaseTransition(
                "task-1",
                PlanningPhaseEnum.ABORTED,
                TaskEventTypeEnum.TASK_CANCELLED
        );
        inOrder.verify(sessionService).get("task-1");
        verify(graphRunner, never()).run(any(), eq("task-1"), any(), any(), any());
    }

    @Test
    void resumeAppendsUserInterventionBeforeContinuingGraph() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        PlanTaskSession current = new PlanTaskSession();
        current.setTaskId("task-1");
        current.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        when(sessionService.get("task-1")).thenReturn(current);
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"),
                eq("给一个大概的参考就好"), eq(null), eq("给一个大概的参考就好")))
                .thenReturn(session);

        service.resume("task-1", "给一个大概的参考就好", false);

        InOrder inOrder = inOrder(taskRuntimeService, graphRunner);
        inOrder.verify(taskRuntimeService).appendUserIntervention("task-1", "给一个大概的参考就好");
        inOrder.verify(graphRunner).run(
                eq(new PlannerSupervisorDecision(PlannerSupervisorAction.CLARIFICATION_REPLY, "clarification reply")),
                eq("task-1"),
                eq("给一个大概的参考就好"),
                eq(null),
                eq("给一个大概的参考就好")
        );
    }

    @Test
    void resumePassesWorkspaceContextIntoGraph() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        PlanTaskSession current = new PlanTaskSession();
        current.setTaskId("task-1");
        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .selectedMessages(java.util.List.of("用户补充选中消息：采购预算100元"))
                .build();
        when(sessionService.get("task-1")).thenReturn(current);
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"),
                eq("继续整理"), eq(workspaceContext), eq("继续整理")))
                .thenReturn(session);

        service.resume("task-1", "继续整理", false, workspaceContext);

        verify(graphRunner).run(
                eq(new PlannerSupervisorDecision(PlannerSupervisorAction.CLARIFICATION_REPLY, "clarification reply")),
                eq("task-1"),
                eq("继续整理"),
                eq(workspaceContext),
                eq("继续整理")
        );
    }

    @Test
    void replanAppendsUserInterventionBeforeAdjustingPlan() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"),
                eq("增加风险复盘"), eq(null), eq("增加风险复盘")))
                .thenReturn(session);

        service.replan("task-1", "增加风险复盘");

        InOrder inOrder = inOrder(graphRunner, taskBridgeService);
        inOrder.verify(graphRunner).run(
                eq(new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "user requested plan adjustment")),
                eq("task-1"),
                eq("增加风险复盘"),
                eq(null),
                eq("增加风险复盘")
        );
        inOrder.verify(taskBridgeService).ensureTask(session);
    }

    @Test
    void completedReplanGoesThroughPlanAdjustmentGraphWithCommandHints() {
        PlanTaskSession completed = new PlanTaskSession();
        completed.setTaskId("task-1");
        completed.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
        String expectedInstruction = "把第2页标题改成新标题\n产物策略：EDIT_EXISTING\n目标产物ID：artifact-1";
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"),
                eq(expectedInstruction), eq(null), eq(expectedInstruction)))
                .thenReturn(completed);

        PlanTaskSession result = service.replan("task-1", "把第2页标题改成新标题", "EDIT_EXISTING", "artifact-1");

        org.assertj.core.api.Assertions.assertThat(result).isSameAs(completed);
        verify(graphRunner).run(
                eq(new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "user requested plan adjustment")),
                eq("task-1"),
                eq(expectedInstruction),
                eq(null),
                eq(expectedInstruction)
        );
        verify(taskBridgeService).ensureTask(completed);
    }

    @Test
    void resumeCompletedAdjustmentGoesThroughPlanAdjustmentGraph() {
        PlanTaskSession current = new PlanTaskSession();
        current.setTaskId("task-1");
        current.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        current.setIntakeState(TaskIntakeState.builder()
                .pendingAdjustmentInstruction("修改现有 PPT")
                .build());
        PlanTaskSession resumed = new PlanTaskSession();
        resumed.setTaskId("task-1");
        when(sessionService.get("task-1")).thenReturn(current);
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"),
                eq("修改现有 PPT\n用户补充：把第2页标题改成新标题"), eq(null), eq("把第2页标题改成新标题")))
                .thenReturn(resumed);

        PlanTaskSession result = service.resume("task-1", "把第2页标题改成新标题", false);

        org.assertj.core.api.Assertions.assertThat(result).isSameAs(resumed);
        verify(sessionService).saveWithoutVersionChange(current);
        verify(graphRunner).run(
                eq(new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "resume completed task adjustment")),
                eq("task-1"),
                eq("修改现有 PPT\n用户补充：把第2页标题改成新标题"),
                eq(null),
                eq("把第2页标题改成新标题")
        );
        verify(taskRuntimeService, never()).appendUserIntervention(any(), any());
    }

    @Test
    void retryFailedAppendsUserInterventionBeforeRetryExecution() {
        PlanTaskSession failed = new PlanTaskSession();
        failed.setTaskId("task-1");
        PlanTaskSession retrying = new PlanTaskSession();
        retrying.setTaskId("task-1");
        when(plannerRetryService.isRetryable("task-1", failed)).thenReturn(true);
        when(taskCommandFacade.retryExecution("task-1", "请用备用方案重试")).thenReturn(retrying);

        service.retryFailed("task-1", failed, "请用备用方案重试");

        InOrder inOrder = inOrder(plannerRetryService, taskRuntimeService, taskCommandFacade);
        inOrder.verify(plannerRetryService).isRetryable("task-1", failed);
        inOrder.verify(taskRuntimeService).appendUserIntervention("task-1", "请用备用方案重试");
        inOrder.verify(taskCommandFacade).retryExecution("task-1", "请用备用方案重试");
    }
}
