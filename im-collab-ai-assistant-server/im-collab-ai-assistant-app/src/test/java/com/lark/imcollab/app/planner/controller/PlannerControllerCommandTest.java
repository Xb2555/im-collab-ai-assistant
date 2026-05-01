package com.lark.imcollab.app.planner.controller;

import com.lark.imcollab.app.planner.assembler.PlannerViewAssembler;
import com.lark.imcollab.app.planner.assembler.TaskRuntimeViewAssembler;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskStatus;
import com.lark.imcollab.common.domain.TaskType;
import com.lark.imcollab.common.facade.HarnessFacade;
import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.dto.DocumentIterationRequest;
import com.lark.imcollab.common.model.dto.PlanCommandRequest;
import com.lark.imcollab.common.model.dto.PlanRequest;
import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.model.vo.DocumentIterationVO;
import com.lark.imcollab.common.model.vo.PlanPreviewVO;
import com.lark.imcollab.common.model.vo.TaskDetailVO;
import com.lark.imcollab.common.model.vo.TaskListVO;
import com.lark.imcollab.gateway.auth.dto.LarkFrontendUserResponse;
import com.lark.imcollab.gateway.auth.service.LarkOAuthService;
import com.lark.imcollab.harness.document.iteration.service.DocumentIterationExecutionService;
import com.lark.imcollab.planner.service.AsyncPlannerService;
import com.lark.imcollab.planner.config.PlannerProperties;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.SupervisorPlannerService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.service.TaskResultEvaluationService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlannerControllerCommandTest {

    private static final String AUTHORIZATION = "Bearer token";
    private static final LarkFrontendUserResponse USER = new LarkFrontendUserResponse("ou-user", "User", null);

    @Mock private PlannerPlanFacade plannerPlanFacade;
    @Mock private SupervisorPlannerService supervisorPlannerService;
    @Mock private PlannerSessionService sessionService;
    @Mock private TaskRuntimeService taskRuntimeService;
    @Mock private TaskResultEvaluationService evaluationService;
    @Mock private PlannerStateStore repository;
    @Mock private HarnessFacade harnessFacade;
    @Mock private TaskBridgeService taskBridgeService;
    @Mock private AsyncPlannerService asyncPlannerService;
    @Mock private PlannerViewAssembler plannerViewAssembler;
    @Mock private TaskRuntimeViewAssembler taskRuntimeViewAssembler;
    @Mock private LarkOAuthService oauthService;
    @Mock private PlannerProperties plannerProperties;
    @Mock private DocumentIterationExecutionService documentIterationExecutionService;

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
    void confirmExecute_triggersHarness() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setVersion(1);

        when(repository.findSession("task-1")).thenReturn(Optional.of(session));
        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.WAITING_APPROVAL)));
        when(harnessFacade.startExecution("task-1")).thenReturn(Task.builder()
                .taskId("task-1").type(TaskType.WRITE_DOC).status(TaskStatus.EXECUTING)
                .steps(new ArrayList<>()).artifacts(new ArrayList<>())
                .createdAt(Instant.now()).updatedAt(Instant.now()).build());
        when(plannerViewAssembler.toPlanPreview(session)).thenReturn(new PlanPreviewVO(
                "task-1", 1, "EXECUTING", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("CONFIRM_EXECUTE");
        request.setVersion(1);

        controller.command("task-1", request, AUTHORIZATION);

        InOrder inOrder = inOrder(taskBridgeService, harnessFacade);
        inOrder.verify(taskBridgeService).ensureTask(session);
        inOrder.verify(harnessFacade).startExecution("task-1");
        verify(harnessFacade).startExecution("task-1");
        verify(plannerViewAssembler).toPlanPreview(session);
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
        when(supervisorPlannerService.adjustPlan("task-1", "change it", null)).thenReturn(session);
        when(plannerViewAssembler.toPlanPreview(session)).thenReturn(new PlanPreviewVO(
                "task-1", 1, "PLAN_READY", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        controller.command("task-1", request, AUTHORIZATION);

        verifyNoInteractions(harnessFacade);
        verify(supervisorPlannerService).adjustPlan("task-1", "change it", null);
        verify(taskBridgeService).ensureTask(session);
        verify(supervisorPlannerService, never()).resume(anyString(), anyString(), anyBoolean());
    }

    @Test
    void cancelProjectsRuntimeCancelledState() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setVersion(2);
        when(repository.findSession("task-1")).thenReturn(Optional.of(session));
        when(repository.findTask("task-1")).thenReturn(Optional.of(ownedTask("task-1", TaskStatusEnum.WAITING_APPROVAL)));
        when(plannerViewAssembler.toPlanPreview(session)).thenReturn(new PlanPreviewVO(
                "task-1", 2, "ABORTED", "title", "summary", java.util.List.of(), java.util.List.of(), java.util.List.of(), null
        ));

        PlanCommandRequest request = new PlanCommandRequest();
        request.setAction("CANCEL");
        request.setVersion(2);

        BaseResponse<PlanPreviewVO> response = controller.command("task-1", request, AUTHORIZATION);

        assertThat(response.getCode()).isZero();
        assertThat(session.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ABORTED);
        verify(taskRuntimeService).projectPhaseTransition(
                "task-1",
                PlanningPhaseEnum.ABORTED,
                TaskEventTypeEnum.TASK_CANCELLED
        );
        verifyNoInteractions(harnessFacade);
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
        verifyNoInteractions(sessionService, harnessFacade, supervisorPlannerService);
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
    void iterateDocumentDelegatesToExecutionServiceWithOwnedContext() {
        DocumentIterationRequest request = new DocumentIterationRequest();
        request.setDocUrl("https://example.feishu.cn/docx/doc123");
        request.setInstruction("解释一下“风险与边界”这段");
        DocumentIterationVO vo = DocumentIterationVO.builder()
                .taskId("doc-iter-1")
                .planningPhase("COMPLETED")
                .recognizedIntent(DocumentIterationIntentType.EXPLAIN)
                .summary("解释结果")
                .build();
        when(documentIterationExecutionService.execute(any())).thenReturn(vo);

        BaseResponse<DocumentIterationVO> response = controller.iterateDocument(request, AUTHORIZATION);

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo(vo);
        verify(documentIterationExecutionService).execute(argThat(value ->
                value != null
                        && value.getWorkspaceContext() != null
                        && "ou-user".equals(value.getWorkspaceContext().getSenderOpenId())
                        && "GUI".equals(value.getWorkspaceContext().getInputSource())
                        && "https://example.feishu.cn/docx/doc123".equals(value.getDocUrl())
        ));
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
    void otherUsersTaskIsHidden() {
        when(repository.findTask("task-other")).thenReturn(Optional.of(TaskRecord.builder()
                .taskId("task-other")
                .ownerOpenId("ou-other")
                .build()));

        BaseResponse<TaskDetailVO> response = controller.getRuntimeSnapshot("task-other", AUTHORIZATION);

        assertThat(response.getCode()).isEqualTo(40400);
        verify(taskRuntimeService, never()).getSnapshot("task-other");
    }

    private static TaskRecord ownedTask(String taskId, TaskStatusEnum status) {
        return TaskRecord.builder()
                .taskId(taskId)
                .ownerOpenId("ou-user")
                .status(status)
                .build();
    }
}
