package com.lark.imcollab.app.planner.controller;

import com.lark.imcollab.app.planner.service.PlannerCommandApplicationService;
import com.lark.imcollab.app.planner.assembler.PlannerViewAssembler;
import com.lark.imcollab.app.planner.assembler.TaskRuntimeViewAssembler;
import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.dto.PlanCommandRequest;
import com.lark.imcollab.common.model.dto.PlanRequest;
import com.lark.imcollab.common.model.dto.RecommendationExecuteRequest;
import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.model.vo.PlanPreviewVO;
import com.lark.imcollab.common.model.vo.TaskActionVO;
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
    void executeRecommendationRoutesByRecommendationId() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        session.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
        session.setVersion(3);
        PlanTaskSession updated = new PlanTaskSession();
        updated.setTaskId("task-1");
        updated.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);

        when(repository.findSession("task-1")).thenReturn(Optional.of(session));
        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.COMPLETED)));
        when(plannerCommandApplicationService.executeRecommendation(
                eq("task-1"),
                eq("GENERATE_PPT_FROM_DOC"),
                argThat(context -> context != null
                        && "ou-user".equals(context.getSenderOpenId())
                        && "GUI".equals(context.getInputSource()))
        )).thenReturn(updated);
        when(taskRuntimeService.ensureRuntimeProjection("task-1")).thenReturn(null);
        when(plannerViewAssembler.toPlanPreview(updated)).thenReturn(new PlanPreviewVO(
                "task-1", 4, "PLAN_READY", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        RecommendationExecuteRequest request = new RecommendationExecuteRequest();
        request.setVersion(3);

        BaseResponse<PlanPreviewVO> response = controller.executeRecommendation(
                "task-1",
                "GENERATE_PPT_FROM_DOC",
                request,
                AUTHORIZATION
        );

        assertThat(response.getCode()).isZero();
        verify(plannerCommandApplicationService).executeRecommendation(
                eq("task-1"),
                eq("GENERATE_PPT_FROM_DOC"),
                argThat(context -> context != null
                        && "ou-user".equals(context.getSenderOpenId())
                        && "GUI".equals(context.getInputSource()))
        );
    }

    @Test
    void executeRecommendationReturnsOperationErrorWhenRecommendationExpired() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        session.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
        session.setVersion(3);

        when(repository.findSession("task-1")).thenReturn(Optional.of(session));
        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.COMPLETED)));
        when(plannerCommandApplicationService.executeRecommendation(anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("Recommendation not found or not executable: GENERATE_PPT_FROM_DOC"));

        RecommendationExecuteRequest request = new RecommendationExecuteRequest();
        request.setVersion(3);

        BaseResponse<PlanPreviewVO> response = controller.executeRecommendation(
                "task-1",
                "GENERATE_PPT_FROM_DOC",
                request,
                AUTHORIZATION
        );

        assertThat(response.getCode()).isEqualTo(50001);
        assertThat(response.getMessage()).contains("Recommendation not found or not executable");
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
        when(plannerCommandApplicationService.replan(
                eq("task-1"),
                eq("change it"),
                eq(null),
                eq(null),
                argThat(context -> context != null
                        && "ou-user".equals(context.getSenderOpenId())
                        && "GUI".equals(context.getInputSource()))
        )).thenReturn(session);
        when(plannerViewAssembler.toPlanPreview(session)).thenReturn(new PlanPreviewVO(
                "task-1", 1, "PLAN_READY", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        controller.command("task-1", request, AUTHORIZATION);

        verify(plannerCommandApplicationService).replan(
                eq("task-1"),
                eq("change it"),
                eq(null),
                eq(null),
                argThat(context -> context != null
                        && "ou-user".equals(context.getSenderOpenId())
                        && "GUI".equals(context.getInputSource()))
        );
        verify(plannerCommandApplicationService, never()).resume(anyString(), anyString(), anyBoolean());
    }

    @Test
    void replanCommandPassesWorkspaceContextThroughPlannerReplanPath() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        session.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
        session.setVersion(1);
        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .selectedMessages(java.util.List.of("把标题改成 666"))
                .build();

        when(repository.findSession("task-1")).thenReturn(Optional.of(session));
        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.COMPLETED)));
        when(plannerCommandApplicationService.replan("task-1", "change it", "EDIT_EXISTING", "artifact-1", workspaceContext))
                .thenReturn(session);
        when(plannerViewAssembler.toPlanPreview(session)).thenReturn(new PlanPreviewVO(
                "task-1", 1, "COMPLETED", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("REPLAN");
        request.setVersion(1);
        request.setFeedback("change it");
        request.setArtifactPolicy("EDIT_EXISTING");
        request.setTargetArtifactId("artifact-1");
        request.setWorkspaceContext(workspaceContext);

        BaseResponse<PlanPreviewVO> response = controller.command("task-1", request, AUTHORIZATION);

        assertThat(response.getCode()).isZero();
        verify(plannerCommandApplicationService).replan("task-1", "change it", "EDIT_EXISTING", "artifact-1", workspaceContext);
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
        when(plannerCommandApplicationService.replan(
                eq("task-1"),
                eq("change it"),
                eq(null),
                eq(null),
                argThat(context -> context != null
                        && "ou-user".equals(context.getSenderOpenId())
                        && "GUI".equals(context.getInputSource()))
        )).thenReturn(wrongTask);

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
    void replanDuringExecutingRequiresInterruptReplanCommand() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        session.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
        session.setVersion(1);
        when(repository.findSession("task-1")).thenReturn(Optional.of(session));
        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.EXECUTING)));

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("REPLAN");
        request.setVersion(1);
        request.setFeedback("加一页风险分析");

        BaseResponse<PlanPreviewVO> response = controller.command("task-1", request, AUTHORIZATION);

        assertThat(response.getCode()).isEqualTo(50001);
        assertThat(response.getMessage()).contains("INTERRUPT_REPLAN");
        verify(plannerCommandApplicationService, never()).replan(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void interruptReplanCommandKeepsOriginalTaskIdAndPassesAutoExecute() {
        PlanTaskSession executing = new PlanTaskSession();
        executing.setTaskId("task-1");
        executing.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
        executing.setVersion(1);
        PlanTaskSession ready = new PlanTaskSession();
        ready.setTaskId("task-1");
        ready.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
        ready.setVersion(2);

        when(repository.findSession("task-1")).thenReturn(Optional.of(executing));
        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.EXECUTING)));
        when(plannerCommandApplicationService.interruptReplan(
                eq("task-1"),
                eq("加一页风险分析"),
                argThat(context -> context != null
                        && "ou-user".equals(context.getSenderOpenId())
                        && "GUI".equals(context.getInputSource())),
                eq(true)
        )).thenReturn(ready);
        when(plannerViewAssembler.toPlanPreview(ready)).thenReturn(new PlanPreviewVO(
                "task-1", 2, "EXECUTING", "title", "summary",
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("INTERRUPT_REPLAN");
        request.setVersion(1);
        request.setFeedback("加一页风险分析");
        request.setAutoExecute(true);

        BaseResponse<PlanPreviewVO> response = controller.command("task-1", request, AUTHORIZATION);

        assertThat(response.getCode()).isZero();
        verify(plannerCommandApplicationService).interruptReplan(
                eq("task-1"),
                eq("加一页风险分析"),
                argThat(context -> context != null
                        && "ou-user".equals(context.getSenderOpenId())
                        && "GUI".equals(context.getInputSource())),
                eq(true)
        );
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
        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .selectedMessages(java.util.List.of("补充材料：使用通用知识即可"))
                .build();
        when(plannerCommandApplicationService.resume("task-1", "使用通用知识即可", false, workspaceContext)).thenReturn(ready);
        when(plannerViewAssembler.toPlanPreview(ready)).thenReturn(new PlanPreviewVO(
                "task-1", 3, "PLAN_READY", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("RESUME");
        request.setVersion(2);
        request.setFeedback("使用通用知识即可");
        request.setWorkspaceContext(workspaceContext);

        BaseResponse<PlanPreviewVO> response = controller.command("task-1", request, AUTHORIZATION);

        assertThat(response.getCode()).isZero();
        verify(plannerCommandApplicationService).resume("task-1", "使用通用知识即可", false, workspaceContext);
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
                "task-1", 2, "ABORTED", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(),
                new TaskActionVO(false, true, false, false, false, false)
        ));

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("CANCEL");
        request.setVersion(2);

        BaseResponse<PlanPreviewVO> response = controller.command("task-1", request, AUTHORIZATION);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData().actions().canReplan()).isTrue();
        assertThat(response.getData().actions().canCancel()).isFalse();
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
        when(plannerCommandApplicationService.retryFailed("task-1", failed, "请用备用方案重试")).thenReturn(retrying);
        when(plannerViewAssembler.toPlanPreview(retrying)).thenReturn(new PlanPreviewVO(
                "task-1", 5, "EXECUTING", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("RETRY_FAILED");
        request.setVersion(4);
        request.setFeedback("请用备用方案重试");

        BaseResponse<PlanPreviewVO> response = controller.command("task-1", request, AUTHORIZATION);

        assertThat(response.getCode()).isZero();
        verify(plannerCommandApplicationService).retryFailed("task-1", failed, "请用备用方案重试");
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
    void activeTasksIncludesCompletedAndCancelledTasksForGuiRefreshRecovery() {
        TaskRecord cancelled = ownedTask("task-cancelled", TaskStatusEnum.CANCELLED);
        when(repository.findTasksByOwner(eq("ou-user"), argThat(statuses ->
                statuses != null
                        && statuses.contains(TaskStatusEnum.EXECUTING)
                        && statuses.contains(TaskStatusEnum.FAILED)
                        && statuses.contains(TaskStatusEnum.COMPLETED)
                        && statuses.contains(TaskStatusEnum.CANCELLED)
        ), eq(0), eq(21))).thenReturn(java.util.List.of(cancelled));
        when(taskRuntimeViewAssembler.toTaskSummary(cancelled)).thenReturn(new com.lark.imcollab.common.model.vo.TaskSummaryVO(
                "task-cancelled", 0, "cancelled", "goal", "CANCELLED", "CANCELLED", 0, false, java.util.List.of(), null, null
        ));

        BaseResponse<TaskListVO> response = controller.listMyActiveTasks(AUTHORIZATION, 20, null);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData().tasks()).singleElement()
                .extracting(com.lark.imcollab.common.model.vo.TaskSummaryVO::taskId)
                .isEqualTo("task-cancelled");
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
