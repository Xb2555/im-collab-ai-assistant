package com.lark.imcollab.app.planner.assembler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.NextStepRecommendation;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.common.model.enums.NextStepRecommendationCodeEnum;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.model.vo.TaskDetailVO;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskRuntimeViewAssemblerTest {

    private final PlannerStateStore stateStore = mock(PlannerStateStore.class);
    private final TaskRuntimeViewAssembler assembler = new TaskRuntimeViewAssembler(stateStore, new ObjectMapper());

    @Test
    void failedTaskCanRetry() {
        TaskDetailVO detail = assembler.toTaskDetail(TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder()
                        .taskId("task-1")
                        .status(TaskStatusEnum.FAILED)
                        .build())
                .build());

        assertThat(detail.actions().canRetry()).isTrue();
        assertThat(detail.actions().canConfirm()).isFalse();
    }

    @Test
    void completedTaskCanReplanButCannotCancelOrRetry() {
        TaskDetailVO detail = assembler.toTaskDetail(TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder()
                        .taskId("task-1")
                        .status(TaskStatusEnum.COMPLETED)
                        .build())
                .build());

        assertThat(detail.actions().canReplan()).isTrue();
        assertThat(detail.actions().canCancel()).isFalse();
        assertThat(detail.actions().canRetry()).isFalse();
    }

    @Test
    void cancelledTaskCanReplanButCannotCancel() {
        TaskDetailVO detail = assembler.toTaskDetail(TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder()
                        .taskId("task-1")
                        .status(TaskStatusEnum.CANCELLED)
                        .build())
                .build());

        assertThat(detail.actions().canReplan()).isTrue();
        assertThat(detail.actions().canCancel()).isFalse();
        assertThat(detail.actions().canConfirm()).isFalse();
    }

    @Test
    void executingTaskCanInterruptReplanButCannotPlainReplan() {
        TaskDetailVO detail = assembler.toTaskDetail(TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder()
                        .taskId("task-1")
                        .status(TaskStatusEnum.EXECUTING)
                        .build())
                .steps(java.util.List.of(TaskStepRecord.builder()
                        .taskId("task-1")
                        .stepId("step-1")
                        .status(StepStatusEnum.RUNNING)
                        .build()))
                .build());

        assertThat(detail.actions().canInterrupt()).isTrue();
        assertThat(detail.actions().canInterruptReplan()).isTrue();
        assertThat(detail.actions().canReplan()).isFalse();
        assertThat(detail.actions().canCancel()).isTrue();
    }

    @Test
    void taskDetailIncludesLatestEvaluationRecommendations() {
        when(stateStore.findLatestEvaluation("task-1")).thenReturn(Optional.of(TaskResultEvaluation.builder()
                .taskId("task-1")
                .agentTaskId("harness")
                .verdict(ResultVerdictEnum.PASS)
                .suggestions(java.util.List.of("保留现有结构"))
                .nextStepRecommendations(java.util.List.of(NextStepRecommendation.builder()
                        .code(NextStepRecommendationCodeEnum.GENERATE_PPT_FROM_DOC)
                        .recommendationId("GENERATE_PPT_FROM_DOC")
                        .title("基于当前文档生成一版汇报 PPT")
                        .reason("文档内容已经齐备，适合继续沉淀成汇报材料。")
                        .suggestedUserInstruction("基于这份文档生成一版汇报PPT")
                        .targetDeliverable(ArtifactTypeEnum.PPT)
                        .followUpMode(FollowUpModeEnum.CONTINUE_CURRENT_TASK)
                        .targetTaskId("task-1")
                        .sourceArtifactId("artifact-doc")
                        .sourceArtifactType(ArtifactTypeEnum.DOC)
                        .plannerInstruction("保留现有文档，基于该文档新增一份汇报PPT初稿。")
                        .artifactPolicy("KEEP_EXISTING_CREATE_NEW")
                        .priority(1)
                        .build()))
                .build()));

        TaskDetailVO detail = assembler.toTaskDetail(TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder()
                        .taskId("task-1")
                        .status(TaskStatusEnum.COMPLETED)
                        .build())
                .build());

        assertThat(detail.evaluation()).isNotNull();
        assertThat(detail.evaluation().verdict()).isEqualTo("PASS");
        assertThat(detail.evaluation().nextStepRecommendations()).hasSize(1);
        assertThat(detail.evaluation().nextStepRecommendations().get(0).code()).isEqualTo("GENERATE_PPT_FROM_DOC");
        assertThat(detail.evaluation().nextStepRecommendations().get(0).recommendationId()).isEqualTo("GENERATE_PPT_FROM_DOC");
        assertThat(detail.evaluation().nextStepRecommendations().get(0).actionLabel()).isEqualTo("生成 PPT");
        assertThat(detail.evaluation().nextStepRecommendations().get(0).executable()).isTrue();
        assertThat(detail.evaluation().nextStepRecommendations().get(0).targetDeliverable()).isEqualTo("PPT");
        assertThat(detail.evaluation().nextStepRecommendations().get(0).followUpMode()).isEqualTo("CONTINUE_CURRENT_TASK");
        assertThat(detail.evaluation().nextStepRecommendations().get(0).targetTaskId()).isEqualTo("task-1");
    }

    @Test
    void nonCompletedTaskDoesNotExposeStaleEvaluationRecommendations() {
        when(stateStore.findLatestEvaluation("task-1")).thenReturn(Optional.of(TaskResultEvaluation.builder()
                .taskId("task-1")
                .verdict(ResultVerdictEnum.PASS)
                .nextStepRecommendations(java.util.List.of(NextStepRecommendation.builder()
                        .recommendationId("GENERATE_SHAREABLE_SUMMARY")
                        .build()))
                .build()));

        TaskDetailVO detail = assembler.toTaskDetail(TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder()
                        .taskId("task-1")
                        .status(TaskStatusEnum.WAITING_APPROVAL)
                        .build())
                .build());

        assertThat(detail.evaluation()).isNull();
    }
}
