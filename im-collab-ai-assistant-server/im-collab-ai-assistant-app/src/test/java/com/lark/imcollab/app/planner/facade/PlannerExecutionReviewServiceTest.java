package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.facade.TaskUserNotificationFacade;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskResultEvaluationService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannerExecutionReviewServiceTest {

    private final PlannerSessionService sessionService = mock(PlannerSessionService.class);
    private final TaskRuntimeService taskRuntimeService = mock(TaskRuntimeService.class);
    private final TaskResultEvaluationService evaluationService = mock(TaskResultEvaluationService.class);
    private final PlannerStateStore stateStore = mock(PlannerStateStore.class);
    private final TaskUserNotificationFacade notificationFacade = mock(TaskUserNotificationFacade.class);
    private final PlannerExecutionReviewService service = new PlannerExecutionReviewService(
            sessionService,
            taskRuntimeService,
            evaluationService,
            stateStore,
            List.of(notificationFacade)
    );

    @Test
    void reviewsHarnessResultAndNotifiesUserFromPlannerNode() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .build();
        ArtifactRecord artifact = ArtifactRecord.builder()
                .artifactId("artifact-1")
                .taskId("task-1")
                .type(ArtifactTypeEnum.DOC)
                .title("技术方案文档")
                .build();
        TaskRuntimeSnapshot snapshot = TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder().taskId("task-1").status(TaskStatusEnum.COMPLETED).build())
                .artifacts(List.of(artifact))
                .build();
        TaskResultEvaluation evaluation = TaskResultEvaluation.builder()
                .taskId("task-1")
                .agentTaskId("harness")
                .verdict(ResultVerdictEnum.PASS)
                .build();
        when(sessionService.get("task-1")).thenReturn(session);
        when(taskRuntimeService.getSnapshot("task-1")).thenReturn(snapshot);
        when(evaluationService.evaluate(org.mockito.ArgumentMatchers.any(TaskSubmissionResult.class)))
                .thenReturn(evaluation);

        service.reviewAndNotify("task-1");

        ArgumentCaptor<TaskSubmissionResult> submissionCaptor = ArgumentCaptor.forClass(TaskSubmissionResult.class);
        verify(stateStore).saveSubmission(submissionCaptor.capture());
        assertThat(submissionCaptor.getValue().getArtifactRefs()).containsExactly("artifact-1");
        assertThat(session.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        verify(sessionService).save(session);
        verify(taskRuntimeService).syncTaskState("task-1", PlanningPhaseEnum.COMPLETED);
        verify(sessionService).publishEvent("task-1", "COMPLETED");
        verify(notificationFacade).notifyExecutionReviewed(session, snapshot, evaluation);
    }

    @Test
    void marksReviewFailedWhenHarnessLeavesActiveStepsUnfinished() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .version(4)
                .build();
        TaskRecord task = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.COMPLETED)
                .version(4)
                .build();
        TaskRuntimeSnapshot snapshot = TaskRuntimeSnapshot.builder()
                .task(task)
                .steps(List.of(
                        TaskStepRecord.builder()
                                .stepId("card-001")
                                .name("生成技术方案文档")
                                .status(StepStatusEnum.COMPLETED)
                                .build(),
                        TaskStepRecord.builder()
                                .stepId("card-002")
                                .name("生成项目进展摘要")
                                .status(StepStatusEnum.READY)
                                .build()
                ))
                .artifacts(List.of(ArtifactRecord.builder()
                        .artifactId("artifact-1")
                        .taskId("task-1")
                        .type(ArtifactTypeEnum.DOC)
                        .title("技术方案文档")
                        .build()))
                .build();
        when(sessionService.get("task-1")).thenReturn(session);
        when(taskRuntimeService.getSnapshot("task-1")).thenReturn(snapshot);

        service.reviewAndNotify("task-1");

        assertThat(session.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.FAILED);
        assertThat(session.getTransitionReason()).contains("剩余未完成", "生成项目进展摘要");
        assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.FAILED);
        assertThat(task.getProgress()).isEqualTo(50);
        verify(stateStore, never()).saveSubmission(org.mockito.ArgumentMatchers.any());
        verify(evaluationService, never()).evaluate(org.mockito.ArgumentMatchers.any());
        verify(taskRuntimeService).syncTaskState("task-1", PlanningPhaseEnum.FAILED);
        verify(sessionService).publishEvent("task-1", "FAILED");
        verify(notificationFacade).notifyExecutionReviewed(session, snapshot, null);
    }

    @Test
    void keepsRuntimeWaitingApprovalWhenHarnessRequestsHumanReview() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.EXECUTING)
                .version(4)
                .build();
        TaskRecord task = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.WAITING_APPROVAL)
                .version(4)
                .build();
        TaskRuntimeSnapshot snapshot = TaskRuntimeSnapshot.builder()
                .task(task)
                .steps(List.of(
                        TaskStepRecord.builder()
                                .stepId("card-001")
                                .name("生成技术方案文档")
                                .status(StepStatusEnum.WAITING_APPROVAL)
                                .build(),
                        TaskStepRecord.builder()
                                .stepId("card-002")
                                .name("生成汇报PPT")
                                .status(StepStatusEnum.READY)
                                .build()
                ))
                .build();
        when(sessionService.get("task-1")).thenReturn(session);
        when(taskRuntimeService.getSnapshot("task-1")).thenReturn(snapshot);
        when(stateStore.findRuntimeEventsByTaskId("task-1")).thenReturn(List.of(
                TaskEventRecord.builder()
                        .taskId("task-1")
                        .type(TaskEventTypeEnum.PLAN_APPROVAL_REQUIRED)
                        .payloadJson("\"文档审核发现问题：风险责任人待定\"")
                        .build()
        ));

        service.reviewAndNotify("task-1");

        assertThat(session.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ASK_USER);
        assertThat(session.getTransitionReason()).contains("风险责任人待定");
        assertThat(task.getStatus()).isEqualTo(TaskStatusEnum.WAITING_APPROVAL);
        ArgumentCaptor<TaskResultEvaluation> evaluationCaptor = ArgumentCaptor.forClass(TaskResultEvaluation.class);
        verify(stateStore).saveEvaluation(evaluationCaptor.capture());
        assertThat(evaluationCaptor.getValue().getVerdict()).isEqualTo(ResultVerdictEnum.HUMAN_REVIEW);
        assertThat(evaluationCaptor.getValue().getIssues()).containsExactly("文档审核发现问题：风险责任人待定");
        verify(stateStore, never()).saveTask(org.mockito.ArgumentMatchers.any());
        verify(taskRuntimeService, never()).syncTaskState(org.mockito.ArgumentMatchers.eq("task-1"), org.mockito.ArgumentMatchers.any());
        verify(sessionService).publishEvent("task-1", "ASK_USER");
        verify(notificationFacade).notifyExecutionReviewed(session, snapshot, evaluationCaptor.getValue());
    }
}
