package com.lark.imcollab.planner.clarification;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PromptSlotState;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClarificationServiceTest {

    private final ClarificationService service = new ClarificationService();

    @Test
    void askingUserLimitsQuestionsToThreeAndCreatesPromptSlots() {
        PlanTaskSession session = PlanTaskSession.builder().taskId("task-1").build();

        service.askUser(session, List.of("目标受众是谁？", "输出文档还是 PPT？", "时间范围？", "语气？"));

        assertThat(session.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ASK_USER);
        assertThat(session.getClarificationQuestions()).containsExactly("目标受众是谁？", "输出文档还是 PPT？", "时间范围？");
        assertThat(session.getActivePromptSlots()).hasSize(3);
        assertThat(session.getTransitionReason()).isEqualTo("Information insufficient");
    }

    @Test
    void absorbAnswerMarksSlotsAndMaintainsClarifiedInstruction() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-2")
                .rawInstruction("帮我写一份架构说明")
                .activePromptSlots(List.of(PromptSlotState.builder()
                        .slotKey("clarification-1")
                        .prompt("目标受众是谁？")
                        .answered(false)
                        .build()))
                .build();

        service.absorbAnswer(session, "面向技术评审，需要 Mermaid 架构图");

        assertThat(session.getClarificationAnswers()).containsExactly("面向技术评审，需要 Mermaid 架构图");
        assertThat(session.getActivePromptSlots()).allMatch(PromptSlotState::isAnswered);
        assertThat(session.getClarifiedInstruction())
                .contains("帮我写一份架构说明")
                .contains("面向技术评审，需要 Mermaid 架构图");
    }
}
