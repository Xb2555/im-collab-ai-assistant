package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskIntakeServiceTests {

    private final TaskIntakeService service = new TaskIntakeService();

    @Test
    void shouldTreatAskUserFollowupAsClarificationReply() {
        PlanTaskSession session = PlanTaskSession.builder()
                .planningPhase(PlanningPhaseEnum.ASK_USER)
                .build();

        TaskIntakeDecision decision = service.decide(session, "补充一下时间范围", null, true);

        assertThat(decision.intakeType()).isEqualTo(TaskIntakeTypeEnum.CLARIFICATION_REPLY);
        assertThat(decision.effectiveInput()).isEqualTo("补充一下时间范围");
    }

    @Test
    void shouldTreatStatusQuestionAsStatusQuery() {
        PlanTaskSession session = PlanTaskSession.builder()
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        TaskIntakeDecision decision = service.decide(session, "当前进度怎么样", null, true);

        assertThat(decision.intakeType()).isEqualTo(TaskIntakeTypeEnum.STATUS_QUERY);
    }

    @Test
    void shouldTreatExistingPlanMessageAsAdjustment() {
        PlanTaskSession session = PlanTaskSession.builder()
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        TaskIntakeDecision decision = service.decide(session, "把输出改成文档加PPT", null, true);

        assertThat(decision.intakeType()).isEqualTo(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
    }

    @Test
    void shouldTreatFirstMessageAsNewTask() {
        TaskIntakeDecision decision = service.decide(null, "生成周报", null, false);

        assertThat(decision.intakeType()).isEqualTo(TaskIntakeTypeEnum.NEW_TASK);
    }
}
