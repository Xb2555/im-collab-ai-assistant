package com.lark.imcollab.app.planner.assembler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.model.vo.TaskDetailVO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRuntimeViewAssemblerTest {

    private final TaskRuntimeViewAssembler assembler = new TaskRuntimeViewAssembler(new ObjectMapper());

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
}
