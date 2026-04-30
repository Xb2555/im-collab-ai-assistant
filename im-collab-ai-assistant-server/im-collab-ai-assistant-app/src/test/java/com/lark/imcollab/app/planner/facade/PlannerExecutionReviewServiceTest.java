package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.facade.TaskUserNotificationFacade;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
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
        verify(sessionService).publishEvent("task-1", "COMPLETED");
        verify(notificationFacade).notifyExecutionReviewed(session, snapshot, evaluation);
    }
}
