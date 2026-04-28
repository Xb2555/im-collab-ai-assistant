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

        TaskIntakeDecision decision = service.decide(session, "\u8865\u5145\u4e00\u4e0b\u65f6\u95f4\u8303\u56f4", null, true);

        assertThat(decision.intakeType()).isEqualTo(TaskIntakeTypeEnum.CLARIFICATION_REPLY);
        assertThat(decision.effectiveInput()).isEqualTo("\u8865\u5145\u4e00\u4e0b\u65f6\u95f4\u8303\u56f4");
    }

    @Test
    void shouldTreatStatusQuestionAsStatusQuery() {
        PlanTaskSession session = PlanTaskSession.builder()
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        TaskIntakeDecision decision = service.decide(session, "\u5f53\u524d\u8fdb\u5ea6\u600e\u4e48\u6837", null, true);

        assertThat(decision.intakeType()).isEqualTo(TaskIntakeTypeEnum.STATUS_QUERY);
    }

    @Test
    void shouldTreatExistingPlanMessageAsAdjustment() {
        PlanTaskSession session = PlanTaskSession.builder()
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        TaskIntakeDecision decision = service.decide(session, "\u628a\u8f93\u51fa\u6539\u6210\u6587\u6863\u52a0PPT", null, true);

        assertThat(decision.intakeType()).isEqualTo(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
    }

    @Test
    void shouldTreatExistingSessionCancelMessageAsCancelTask() {
        PlanTaskSession session = PlanTaskSession.builder()
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .build();

        TaskIntakeDecision decision = service.decide(session, "\u53d6\u6d88\u4efb\u52a1", null, true);

        assertThat(decision.intakeType()).isEqualTo(TaskIntakeTypeEnum.CANCEL_TASK);
    }

    @Test
    void shouldTreatFirstMessageAsNewTask() {
        TaskIntakeDecision decision = service.decide(null, "\u751f\u6210\u5468\u62a5", null, false);

        assertThat(decision.intakeType()).isEqualTo(TaskIntakeTypeEnum.NEW_TASK);
    }
}
