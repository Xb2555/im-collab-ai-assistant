package com.lark.imcollab.app.planner.assembler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
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
}
