package com.lark.imcollab.app.planner.assembler;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.model.vo.PlanPreviewVO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerViewAssemblerTest {

    private final PlannerViewAssembler assembler = new PlannerViewAssembler();

    @Test
    void transientReplyPreviewDisablesRuntimeAndActions() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("transient-task")
                .planningPhase(PlanningPhaseEnum.INTAKE)
                .intakeState(TaskIntakeState.builder()
                        .intakeType(TaskIntakeTypeEnum.UNKNOWN)
                        .assistantReply("我在。你把想整理的材料或目标发我就行。")
                        .build())
                .build();

        PlanPreviewVO preview = assembler.toPlanPreview(session);

        assertThat(preview.accepted()).isFalse();
        assertThat(preview.runtimeAvailable()).isFalse();
        assertThat(preview.transientReply()).isTrue();
        assertThat(preview.assistantReply()).contains("我在");
        assertThat(preview.actions().canConfirm()).isFalse();
        assertThat(preview.actions().canReplan()).isFalse();
        assertThat(preview.actions().canCancel()).isFalse();
        assertThat(preview.actions().canResume()).isFalse();
        assertThat(preview.actions().canInterrupt()).isFalse();
        assertThat(preview.actions().canRetry()).isFalse();
    }

    @Test
    void normalPreviewKeepsRuntimeAvailable() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        PlanPreviewVO preview = assembler.toPlanPreview(session);

        assertThat(preview.accepted()).isTrue();
        assertThat(preview.runtimeAvailable()).isTrue();
        assertThat(preview.transientReply()).isFalse();
        assertThat(preview.actions().canConfirm()).isTrue();
        assertThat(preview.actions().canReplan()).isTrue();
    }

    @Test
    void completedPreviewCanReplanOnly() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.COMPLETED)
                .build();

        PlanPreviewVO preview = assembler.toPlanPreview(session);

        assertThat(preview.actions().canReplan()).isTrue();
        assertThat(preview.actions().canCancel()).isFalse();
        assertThat(preview.actions().canRetry()).isFalse();
    }

    @Test
    void abortedPreviewCanReplanOnly() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.ABORTED)
                .aborted(true)
                .build();

        PlanPreviewVO preview = assembler.toPlanPreview(session);

        assertThat(preview.actions().canConfirm()).isFalse();
        assertThat(preview.actions().canReplan()).isTrue();
        assertThat(preview.actions().canCancel()).isFalse();
        assertThat(preview.actions().canResume()).isFalse();
        assertThat(preview.actions().canInterrupt()).isFalse();
        assertThat(preview.actions().canRetry()).isFalse();
    }

    @Test
    void abortedFlagDisablesPlanReadyActionsExceptReplan() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .aborted(true)
                .build();

        PlanPreviewVO preview = assembler.toPlanPreview(session);

        assertThat(preview.actions().canConfirm()).isFalse();
        assertThat(preview.actions().canReplan()).isTrue();
        assertThat(preview.actions().canCancel()).isFalse();
    }
}
