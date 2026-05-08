package com.lark.imcollab.app.planner.service;

import com.lark.imcollab.common.facade.DocumentArtifactIterationFacade;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.DocumentArtifactIterationStatus;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.facade.ImTaskCommandFacade;
import com.lark.imcollab.common.model.vo.DocumentArtifactIterationResult;
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
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlannerCommandApplicationServiceTest {

    @Mock private PlannerSupervisorGraphRunner graphRunner;
    @Mock private TaskBridgeService taskBridgeService;
    @Mock private PlannerRetryService plannerRetryService;
    @Mock private ImTaskCommandFacade taskCommandFacade;
    @Mock private TaskRuntimeService taskRuntimeService;
    @Mock private PlannerSessionService sessionService;
    @Mock private DocumentArtifactIterationFacade documentArtifactIterationFacade;

    private PlannerCommandApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PlannerCommandApplicationService(
                graphRunner,
                taskBridgeService,
                plannerRetryService,
                taskCommandFacade,
                taskRuntimeService,
                sessionService,
                provider(documentArtifactIterationFacade)
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
    void interruptReplanSupersedesExecutionWithoutMarkingTaskAborted() {
        PlanTaskSession executing = new PlanTaskSession();
        executing.setTaskId("task-1");
        executing.setPlanningPhase(PlanningPhaseEnum.EXECUTING);
        executing.setActiveExecutionAttemptId("attempt-old");
        executing.setVersion(3);
        executing.setClarifiedInstruction("旧澄清");
        executing.setClarificationAnswers(java.util.List.of("旧回答"));
        executing.setExecutionContract(ExecutionContract.builder().taskBrief("旧合同").build());
        executing.setPlanBlueprint(PlanBlueprint.builder()
                .taskBrief("旧计划")
                .constraints(java.util.List.of("标题必须包含 OLD_PLAN"))
                .successCriteria(java.util.List.of("旧成功标准"))
                .risks(java.util.List.of("旧风险"))
                .planCards(java.util.List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .title("旧卡片")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .build());
        executing.setIntentSnapshot(IntentSnapshot.builder()
                .constraints(java.util.List.of("旧意图约束"))
                .build());
        PlanTaskSession replanning = new PlanTaskSession();
        replanning.setTaskId("task-1");
        replanning.setPlanningPhase(PlanningPhaseEnum.INTERRUPTING);
        replanning.setActiveExecutionAttemptId("attempt-old");
        replanning.setVersion(4);
        replanning.setClarifiedInstruction("旧澄清");
        replanning.setClarificationAnswers(java.util.List.of("旧回答"));
        replanning.setExecutionContract(ExecutionContract.builder().taskBrief("旧合同").build());
        replanning.setPlanBlueprint(PlanBlueprint.builder()
                .taskBrief("旧计划")
                .constraints(java.util.List.of("标题必须包含 OLD_PLAN"))
                .successCriteria(java.util.List.of("旧成功标准"))
                .risks(java.util.List.of("旧风险"))
                .planCards(java.util.List.of(UserPlanCard.builder()
                        .cardId("card-001")
                        .title("旧卡片")
                        .type(PlanCardTypeEnum.DOC)
                        .build()))
                .build());
        replanning.setIntentSnapshot(IntentSnapshot.builder()
                .constraints(java.util.List.of("旧意图约束"))
                .build());
        PlanTaskSession ready = new PlanTaskSession();
        ready.setTaskId("task-1");
        ready.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        ready.setVersion(5);
        WorkspaceContext workspaceContext = WorkspaceContext.builder().senderOpenId("ou-user").build();

        when(sessionService.get("task-1")).thenReturn(executing, replanning);
        when(graphRunner.run(
                eq(new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "interrupt execution and replan")),
                eq("task-1"),
                eq("增加一页风险分析"),
                eq(workspaceContext),
                eq("增加一页风险分析")))
                .thenReturn(ready);

        PlanTaskSession result = service.interruptReplan("task-1", "增加一页风险分析", workspaceContext, false);

        org.assertj.core.api.Assertions.assertThat(result).isSameAs(ready);
        verify(taskRuntimeService).appendUserIntervention("task-1", "增加一页风险分析");
        verify(taskRuntimeService).projectPhaseTransition(
                "task-1",
                PlanningPhaseEnum.INTERRUPTING,
                TaskEventTypeEnum.EXECUTION_INTERRUPTING
        );
        verify(taskRuntimeService).projectPhaseTransition(
                "task-1",
                PlanningPhaseEnum.REPLANNING,
                TaskEventTypeEnum.PLAN_ADJUSTED
        );
        verify(taskCommandFacade).interruptExecution("task-1");
        verify(taskCommandFacade, never()).cancelExecution("task-1");
        verify(sessionService, never()).markAborted(any(), any());
        verify(sessionService, times(2)).save(any(PlanTaskSession.class));
        verify(sessionService).save(argThat(session ->
                session.getPlanningPhase() == PlanningPhaseEnum.REPLANNING
                        && session.getClarifiedInstruction() == null
                        && session.getExecutionContract() == null
                        && session.getClarificationAnswers() != null
                        && session.getClarificationAnswers().isEmpty()
                        && session.getPlanBlueprint() != null
                        && session.getPlanBlueprint().getConstraints() != null
                        && session.getPlanBlueprint().getConstraints().isEmpty()
                        && session.getPlanBlueprint().getSuccessCriteria() != null
                        && session.getPlanBlueprint().getSuccessCriteria().isEmpty()
                        && session.getPlanBlueprint().getRisks() != null
                        && session.getPlanBlueprint().getRisks().isEmpty()
                        && session.getIntentSnapshot() != null
                        && session.getIntentSnapshot().getConstraints() != null
                        && session.getIntentSnapshot().getConstraints().isEmpty()
        ));
        verify(taskBridgeService).ensureTask(ready);
    }

    @Test
    void replanPassesWorkspaceContextIntoGraph() {
        PlanTaskSession session = new PlanTaskSession();
        session.setTaskId("task-1");
        WorkspaceContext workspaceContext = WorkspaceContext.builder()
                .senderOpenId("ou-user")
                .selectedMessages(java.util.List.of("补充材料"))
                .build();
        String expectedInstruction = "把文档标题改成 666\n产物策略：EDIT_EXISTING\n目标产物ID：artifact-1";
        when(graphRunner.run(any(PlannerSupervisorDecision.class), eq("task-1"),
                eq(expectedInstruction), eq(workspaceContext), eq(expectedInstruction)))
                .thenReturn(session);

        PlanTaskSession result = service.replan(
                "task-1",
                "把文档标题改成 666",
                "EDIT_EXISTING",
                "artifact-1",
                workspaceContext
        );

        org.assertj.core.api.Assertions.assertThat(result).isSameAs(session);
        verify(graphRunner).run(
                eq(new PlannerSupervisorDecision(PlannerSupervisorAction.PLAN_ADJUSTMENT, "user requested plan adjustment")),
                eq("task-1"),
                eq(expectedInstruction),
                eq(workspaceContext),
                eq(expectedInstruction)
        );
        verify(taskBridgeService).ensureTask(session);
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

    @Test
    void confirmExecutionUsesDocumentApprovalWhenPendingDocApprovalExists() {
        PlanTaskSession current = new PlanTaskSession();
        current.setTaskId("task-1");
        current.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        current.setIntakeState(TaskIntakeState.builder()
                .pendingDocumentIterationTaskId("doc-iter-1")
                .pendingDocumentArtifactId("artifact-doc-1")
                .pendingDocumentDocUrl("https://example.feishu.cn/docx/doc-token")
                .pendingDocumentApprovalMode("COMPLETED_TASK_DOC_APPROVAL")
                .pendingAdjustmentInstruction("修改文档")
                .build());
        when(documentArtifactIterationFacade.decide(
                eq("doc-iter-1"),
                eq("artifact-doc-1"),
                eq("https://example.feishu.cn/docx/doc-token"),
                any(),
                eq(null)))
                .thenReturn(DocumentArtifactIterationResult.builder()
                        .taskId("doc-iter-1")
                        .artifactId("artifact-doc-1")
                        .docUrl("https://example.feishu.cn/docx/doc-token")
                        .status(DocumentArtifactIterationStatus.COMPLETED)
                        .summary("文档修改已执行")
                        .build());

        PlanTaskSession result = service.confirmExecution("task-1", current);

        org.assertj.core.api.Assertions.assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        verify(documentArtifactIterationFacade).decide(
                eq("doc-iter-1"),
                eq("artifact-doc-1"),
                eq("https://example.feishu.cn/docx/doc-token"),
                any(),
                eq(null));
        verify(taskCommandFacade, never()).confirmExecution(any());
    }

    @Test
    void resumeUsesDocumentApprovalModifyWhenPendingDocApprovalExists() {
        PlanTaskSession current = new PlanTaskSession();
        current.setTaskId("task-1");
        current.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        current.setIntakeState(TaskIntakeState.builder()
                .pendingDocumentIterationTaskId("doc-iter-1")
                .pendingDocumentArtifactId("artifact-doc-1")
                .pendingDocumentDocUrl("https://example.feishu.cn/docx/doc-token")
                .pendingDocumentApprovalMode("COMPLETED_TASK_DOC_APPROVAL")
                .pendingAdjustmentInstruction("修改文档")
                .build());
        when(sessionService.get("task-1")).thenReturn(current);
        when(documentArtifactIterationFacade.decide(
                eq("doc-iter-1"),
                eq("artifact-doc-1"),
                eq("https://example.feishu.cn/docx/doc-token"),
                any(),
                eq("ou-user")))
                .thenReturn(DocumentArtifactIterationResult.builder()
                        .taskId("doc-iter-1")
                        .artifactId("artifact-doc-1")
                        .docUrl("https://example.feishu.cn/docx/doc-token")
                        .status(DocumentArtifactIterationStatus.WAITING_APPROVAL)
                        .summary("已按新意见重建待确认计划")
                        .build());

        WorkspaceContext workspaceContext = WorkspaceContext.builder().senderOpenId("ou-user").build();
        PlanTaskSession result = service.resume("task-1", "请把风险章节改成整章重写", false, workspaceContext);

        org.assertj.core.api.Assertions.assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ASK_USER);
        org.assertj.core.api.Assertions.assertThat(result.getIntakeState().getAssistantReply()).isEqualTo("已按新意见重建待确认计划");
        verify(documentArtifactIterationFacade).decide(
                eq("doc-iter-1"),
                eq("artifact-doc-1"),
                eq("https://example.feishu.cn/docx/doc-token"),
                any(),
                eq("ou-user"));
        verify(graphRunner, never()).run(any(), any(), any(), any(), any());
    }

    private static <T> ObjectProvider<T> provider(T facade) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return facade;
            }

            @Override
            public T getIfAvailable() {
                return facade;
            }

            @Override
            public T getIfUnique() {
                return facade;
            }

            @Override
            public T getObject() {
                return facade;
            }

            @Override
            public java.util.Iterator<T> iterator() {
                return java.util.List.of(facade).iterator();
            }

            @Override
            public java.util.stream.Stream<T> stream() {
                return java.util.stream.Stream.of(facade);
            }

            @Override
            public java.util.stream.Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
