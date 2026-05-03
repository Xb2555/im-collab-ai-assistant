package com.lark.imcollab.app.planner.controller;

import com.lark.imcollab.app.planner.service.PlannerCommandApplicationService;
import com.lark.imcollab.app.planner.assembler.PlannerViewAssembler;
import com.lark.imcollab.app.planner.assembler.TaskRuntimeViewAssembler;
import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.dto.PlanCommandRequest;
import com.lark.imcollab.common.model.dto.PlanRequest;
import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.model.vo.PlanPreviewVO;
import com.lark.imcollab.common.model.vo.TaskDetailVO;
import com.lark.imcollab.common.model.vo.TaskListVO;
import com.lark.imcollab.common.model.vo.TaskSummaryVO;
import com.lark.imcollab.gateway.auth.dto.LarkFrontendUserResponse;
import com.lark.imcollab.gateway.auth.service.LarkOAuthService;
import com.lark.imcollab.planner.service.AsyncPlannerService;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskResultEvaluationService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlannerControllerCommandTest {

    private static final String AUTHORIZATION = "Bearer token";
    private static final LarkFrontendUserResponse USER = new LarkFrontendUserResponse("ou-user", "User", null);

    @Mock private PlannerPlanFacade plannerPlanFacade;
    @Mock private PlannerCommandApplicationService plannerCommandApplicationService;
    @Mock private PlannerSessionService sessionService;
    @Mock private TaskRuntimeService taskRuntimeService;
    @Mock private TaskResultEvaluationService evaluationService;
    @Mock private PlannerStateStore repository;
    @Mock private AsyncPlannerService asyncPlannerService;
    @Mock private PlannerViewAssembler plannerViewAssembler;
    @Mock private TaskRuntimeViewAssembler taskRuntimeViewAssembler;
    @Mock private LarkOAuthService oauthService;
    @Mock private PlannerProperties plannerProperties;

    @InjectMocks
    private PlannerController controller;

    @BeforeEach
    void setUp() {
        lenient().when(oauthService.findCurrentUserByBusinessToken("token")).thenReturn(Optional.of(USER));
        PlannerProperties.Auth auth = new PlannerProperties.Auth();
        auth.setEnabled(true);
        lenient().when(plannerProperties.getAuth()).thenReturn(auth);
    }

    @Test
    void planReturnsAsyncAcceptedPreview() {
        PlanRequest request = new PlanRequest();
        request.setRawInstruction("写技术方案");
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-async");
        session.setPlanningPhase(PlanningPhaseEnum.INTAKE);
        PlanPreviewVO preview = new PlanPreviewVO(
                "task-async", 0, "INTAKE", "写技术方案", "已收到任务，正在生成计划",
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        );
        when(asyncPlannerService.submitPlan(eq("写技术方案"), any(), isNull(), isNull())).thenReturn(session);
        when(plannerViewAssembler.toPlanPreview(session)).thenReturn(preview);

        BaseResponse<PlanPreviewVO> response = controller.plan(request, AUTHORIZATION);

        assertThat(response.getData()).isEqualTo(preview);
        verify(asyncPlannerService).submitPlan(eq("写技术方案"), argThat(context ->
                context != null && "ou-user".equals(context.getSenderOpenId()) && "GUI".equals(context.getInputSource())
        ), isNull(), isNull());
        verifyNoInteractions(plannerPlanFacade);
    }

    @Test
    void planSyncKeepsLegacySynchronousFacade() {
        PlanRequest request = new PlanRequest();
        request.setRawInstruction("写技术方案");
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-sync");
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        PlanPreviewVO preview = new PlanPreviewVO(
                "task-sync", 0, "PLAN_READY", "写技术方案", "计划已生成",
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        );
        when(plannerPlanFacade.plan(eq("写技术方案"), any(), isNull(), isNull())).thenReturn(session);
        when(plannerViewAssembler.toPlanPreview(session)).thenReturn(preview);

        BaseResponse<PlanPreviewVO> response = controller.planSync(request, AUTHORIZATION);

        assertThat(response.getData()).isEqualTo(preview);
        verify(plannerPlanFacade).plan(eq("写技术方案"), any(), isNull(), isNull());
        verifyNoInteractions(asyncPlannerService);
    }

    @Test
    void confirmExecuteRoutesThroughPlannerGraphFacade() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setVersion(1);
        PlanTaskSession executing = new PlanTaskSession();
        executing.setTaskId("task-1");
        executing.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
        executing.setVersion(2);

        when(repository.findSession("task-1")).thenReturn(Optional.of(session));
        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.WAITING_APPROVAL)));
        when(plannerCommandApplicationService.confirmExecution("task-1", session)).thenReturn(executing);
        when(plannerViewAssembler.toPlanPreview(executing)).thenReturn(new PlanPreviewVO(
                "task-1", 1, "EXECUTING", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("CONFIRM_EXECUTE");
        request.setVersion(1);

        controller.command("task-1", request, AUTHORIZATION);

        verify(plannerCommandApplicationService).confirmExecution("task-1", session);
        verify(plannerViewAssembler).toPlanPreview(executing);
    }

    @Test
    void nonConfirmAction_doesNotTriggerHarness() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setVersion(1);

        when(repository.findSession("task-1")).thenReturn(Optional.of(session));
        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.WAITING_APPROVAL)));

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("REPLAN");
        request.setVersion(1);
        request.setFeedback("change it");
        when(plannerCommandApplicationService.replan("task-1", "change it")).thenReturn(session);
        when(plannerViewAssembler.toPlanPreview(session)).thenReturn(new PlanPreviewVO(
                "task-1", 1, "PLAN_READY", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        controller.command("task-1", request, AUTHORIZATION);

        verify(plannerCommandApplicationService).replan("task-1", "change it");
        verify(plannerCommandApplicationService, never()).resume(anyString(), anyString(), anyBoolean());
    }

    @Test
    void replanWithDifferentReturnedTaskIdFailsFast() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setVersion(1);
        PlanTaskSession wrongTask = new PlanTaskSession();
        wrongTask.setTaskId("task-2");
        wrongTask.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        wrongTask.setVersion(2);

        when(repository.findSession("task-1")).thenReturn(Optional.of(session));
        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.WAITING_APPROVAL)));
        when(plannerCommandApplicationService.replan("task-1", "change it")).thenReturn(wrongTask);

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("REPLAN");
        request.setVersion(1);
        request.setFeedback("change it");

        BaseResponse<PlanPreviewVO> response = controller.command("task-1", request, AUTHORIZATION);

        assertThat(response.getCode()).isEqualTo(50001);
        assertThat(response.getMessage()).contains("inconsistent task id");
        verify(taskRuntimeService, never()).ensureRuntimeProjection(anyString());
    }

    @Test
    void resumeCommandPassesFeedbackThroughPlannerResumePath() {
        PlanTaskSession asking = new PlanTaskSession();
        asking.setTaskId("task-1");
        asking.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        asking.setVersion(2);
        PlanTaskSession ready = new PlanTaskSession();
        ready.setTaskId("task-1");
        ready.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        ready.setVersion(3);

        when(repository.findSession("task-1")).thenReturn(Optional.of(asking));
        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.CLARIFYING)));
        when(plannerCommandApplicationService.resume("task-1", "使用通用知识即可", false)).thenReturn(ready);
        when(plannerViewAssembler.toPlanPreview(ready)).thenReturn(new PlanPreviewVO(
                "task-1", 3, "PLAN_READY", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("RESUME");
        request.setVersion(2);
        request.setFeedback("使用通用知识即可");

        BaseResponse<PlanPreviewVO> response = controller.command("task-1", request, AUTHORIZATION);

        assertThat(response.getCode()).isZero();
        verify(plannerCommandApplicationService).resume("task-1", "使用通用知识即可", false);
        verify(plannerCommandApplicationService, never()).replan(anyString(), anyString());
        verify(plannerViewAssembler).toPlanPreview(ready);
    }

    @Test
    void cancelProjectsRuntimeCancelledState() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setVersion(2);
        PlanTaskSession aborted = new PlanTaskSession();
        aborted.setTaskId("task-1");
        aborted.setPlanningPhase(PlanningPhaseEnum.ABORTED);
        aborted.setVersion(3);
        when(repository.findSession("task-1")).thenReturn(Optional.of(session));
        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.WAITING_APPROVAL)));
        when(plannerCommandApplicationService.cancel("task-1")).thenReturn(aborted);
        when(plannerViewAssembler.toPlanPreview(aborted)).thenReturn(new PlanPreviewVO(
                "task-1", 2, "ABORTED", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("CANCEL");
        request.setVersion(2);

        BaseResponse<PlanPreviewVO> response = controller.command("task-1", request, AUTHORIZATION);

        assertThat(response.getCode()).isZero();
        verify(plannerCommandApplicationService).cancel("task-1");
        verify(taskRuntimeService, never()).projectPhaseTransition(anyString(), any(), any());
    }

    @Test
    void retryFailedRoutesThroughCommandService() {
        PlanTaskSession failed = new PlanTaskSession();
        failed.setTaskId("task-1");
        failed.setPlanningPhase(PlanningPhaseEnum.FAILED);
        failed.setVersion(4);
        PlanTaskSession retrying = new PlanTaskSession();
        retrying.setTaskId("task-1");
        retrying.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
        retrying.setVersion(5);

        when(repository.findSession("task-1")).thenReturn(Optional.of(failed));
        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.FAILED)));
        when(plannerCommandApplicationService.retryFailed("task-1", failed)).thenReturn(retrying);
        when(plannerViewAssembler.toPlanPreview(retrying)).thenReturn(new PlanPreviewVO(
                "task-1", 5, "EXECUTING", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("RETRY_FAILED");
        request.setVersion(4);

        BaseResponse<PlanPreviewVO> response = controller.command("task-1", request, AUTHORIZATION);

        assertThat(response.getCode()).isZero();
        verify(plannerCommandApplicationService).retryFailed("task-1", failed);
        verify(plannerCommandApplicationService, never()).replan(anyString(), anyString());
    }

    @Test
    void invalidCommandReturnsParamsErrorInsteadOfSystemError() {
        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("NO_SUCH_ACTION");
        request.setVersion(1);

        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.WAITING_APPROVAL)));

        BaseResponse<PlanPreviewVO> response = controller.command("task-1", request, AUTHORIZATION);

        assertThat(response.getCode()).isEqualTo(40000);
        assertThat(response.getMessage()).contains("Unsupported planner command");
        verifyNoInteractions(sessionService, plannerCommandApplicationService);
    }

    @Test
    void unknownTaskReturnsNotFoundConsistently() {
        when(repository.findSession("missing-task")).thenReturn(Optional.empty());

        BaseResponse<PlanPreviewVO> preview = controller.getTask("missing-task", AUTHORIZATION);
        BaseResponse<TaskDetailVO> runtime = controller.getRuntimeSnapshot("missing-task", AUTHORIZATION);
        BaseResponse<java.util.List<com.lark.imcollab.common.model.vo.PlanCardVO>> cards =
                controller.getCards("missing-task", AUTHORIZATION);
        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("CANCEL");
        request.setVersion(1);
        BaseResponse<PlanPreviewVO> command = controller.command("missing-task", request, AUTHORIZATION);

        assertThat(preview.getCode()).isEqualTo(40400);
        assertThat(runtime.getCode()).isEqualTo(40400);
        assertThat(cards.getCode()).isEqualTo(40400);
        assertThat(command.getCode()).isEqualTo(40400);
        assertThat(preview.getMessage()).contains("Task not found");
    }

    @Test
    void listMyTasksRequiresLogin() {
        when(oauthService.findCurrentUserByBusinessToken(null)).thenReturn(Optional.empty());

        BaseResponse<TaskListVO> response = controller.listMyTasks(null, null, 20, null);

        assertThat(response.getCode()).isEqualTo(40100);
    }

    @Test
    void listMyTasksReturnsOnlyCurrentUserTasks() {
        TaskRecord owned = ownedTask("task-owned", TaskStatusEnum.EXECUTING);
        when(repository.findTasksByOwner("ou-user", java.util.List.of(), 0, 21)).thenReturn(java.util.List.of(owned));
        when(taskRuntimeViewAssembler.toTaskSummary(owned)).thenReturn(new com.lark.imcollab.common.model.vo.TaskSummaryVO(
                "task-owned", 0, "title", "goal", "EXECUTING", "EXECUTING", 0, false, java.util.List.of(), null, null
        ));

        BaseResponse<TaskListVO> response = controller.listMyTasks(AUTHORIZATION, null, 20, null);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData().tasks()).singleElement()
                .extracting(com.lark.imcollab.common.model.vo.TaskSummaryVO::taskId)
                .isEqualTo("task-owned");
        verify(repository).findTasksByOwner("ou-user", java.util.List.of(), 0, 21);
    }

    @Test
    void activeTasksIncludesCompletedTasksForGuiRefreshRecovery() {
        TaskRecord completed = ownedTask("task-completed", TaskStatusEnum.COMPLETED);
        when(repository.findTasksByOwner(eq("ou-user"), argThat(statuses ->
                statuses != null
                        && statuses.contains(TaskStatusEnum.EXECUTING)
                        && statuses.contains(TaskStatusEnum.FAILED)
                        && statuses.contains(TaskStatusEnum.COMPLETED)
                        && !statuses.contains(TaskStatusEnum.CANCELLED)
        ), eq(0), eq(21))).thenReturn(java.util.List.of(completed));
        when(taskRuntimeViewAssembler.toTaskSummary(completed)).thenReturn(new com.lark.imcollab.common.model.vo.TaskSummaryVO(
                "task-completed", 0, "done", "goal", "COMPLETED", "COMPLETED", 100, false, java.util.List.of(), null, null
        ));

        BaseResponse<TaskListVO> response = controller.listMyActiveTasks(AUTHORIZATION, 20, null);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData().tasks()).singleElement()
                .extracting(com.lark.imcollab.common.model.vo.TaskSummaryVO::taskId)
                .isEqualTo("task-completed");
    }

    @Test
    void otherUsersTaskIsHidden() {
        when(repository.findTask("task-other")).thenReturn(Optional.of(TaskRecord.builder()
                .taskId("task-other")
                .ownerOpenId("ou-other")
                .build()));

        BaseResponse<TaskDetailVO> response = controller.getRuntimeSnapshot("task-other", AUTHORIZATION);

        assertThat(response.getCode()).isEqualTo(40400);
        verify(taskRuntimeService, never()).ensureRuntimeProjection("task-other");
    }

    @Test
    void runtimeQueryUsesSelfHealingProjectionWhenTaskRecordIsMissing() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-heal");
        session.setPlanningPhase(PlanningPhaseEnum.INTAKE);
        session.setVersion(0);
        TaskRuntimeSnapshot healedSnapshot = TaskRuntimeSnapshot.builder()
                .task(ownedTask("task-heal", TaskStatusEnum.PLANNING))
                .steps(java.util.List.of())
                .artifacts(java.util.List.of())
                .events(java.util.List.of())
                .build();
        TaskDetailVO detail = new TaskDetailVO(
                new TaskSummaryVO("task-heal", 0, "title", "goal", "PLANNING", "INTAKE", 0, false,
                        java.util.List.of(), null, null),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                null
        );

        when(repository.findTask("task-heal")).thenReturn(Optional.empty());
        when(repository.findSession("task-heal")).thenReturn(Optional.of(session));
        when(taskRuntimeService.ensureRuntimeProjection("task-heal")).thenReturn(healedSnapshot);
        when(taskRuntimeViewAssembler.toTaskDetail(healedSnapshot)).thenReturn(detail);

        BaseResponse<TaskDetailVO> response = controller.getRuntimeSnapshot("task-heal", AUTHORIZATION);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo(detail);
        verify(taskRuntimeService).ensureRuntimeProjection("task-heal");
    }

    @Test
    void transientRuntimeQueryDoesNotReturnNotFoundWhenSessionExists() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-transient");
        session.setPlanningPhase(PlanningPhaseEnum.INTAKE);
        session.setIntakeState(TaskIntakeState.builder()
                .intakeType(TaskIntakeTypeEnum.UNKNOWN)
                .assistantReply("我在")
                .build());
        TaskRuntimeSnapshot snapshot = TaskRuntimeSnapshot.builder()
                .task(null)
                .steps(java.util.List.of())
                .artifacts(java.util.List.of())
                .events(java.util.List.of())
                .build();
        TaskDetailVO detail = new TaskDetailVO(null, java.util.List.of(), java.util.List.of(), java.util.List.of(), null);

        when(repository.findTask("task-transient")).thenReturn(Optional.empty());
        when(repository.findSession("task-transient")).thenReturn(Optional.of(session));
        when(taskRuntimeService.ensureRuntimeProjection("task-transient")).thenReturn(snapshot);
        when(taskRuntimeViewAssembler.toTaskDetail(snapshot)).thenReturn(detail);

        BaseResponse<TaskDetailVO> response = controller.getRuntimeSnapshot("task-transient", AUTHORIZATION);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo(detail);
    }

    private static TaskRecord ownedTask(String taskId, TaskStatusEnum status) {
        return TaskRecord.builder()
                .taskId(taskId)
                .ownerOpenId("ou-user")
                .status(status)
                .build();
    }
}
