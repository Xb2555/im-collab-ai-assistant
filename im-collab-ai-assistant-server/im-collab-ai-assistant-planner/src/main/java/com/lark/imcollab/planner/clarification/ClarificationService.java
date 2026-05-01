package com.lark.imcollab.planner.clarification;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PromptSlotState;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ClarificationService {

    public PlanTaskSession askUser(PlanTaskSession session, List<String> questions) {
        List<String> conciseQuestions = questions == null
                ? List.of()
                : questions.stream()
                .filter(question -> question != null && !question.isBlank())
                .map(String::trim)
                .distinct()
                .limit(3)
                .toList();
        session.setClarificationQuestions(conciseQuestions);
        session.setActivePromptSlots(toPromptSlots(conciseQuestions));
        session.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        session.setTransitionReason("Information insufficient");
        return session;
    }

    public PlanTaskSession absorbAnswer(PlanTaskSession session, String answer) {
        String normalizedAnswer = answer == null ? "" : answer.trim();
        if (!normalizedAnswer.isBlank()) {
            List<String> answers = new ArrayList<>(session.getClarificationAnswers() == null
                    ? List.of()
                    : session.getClarificationAnswers());
            answers.add(normalizedAnswer);
            session.setClarificationAnswers(answers);
        }
        if (session.getActivePromptSlots() != null) {
            session.getActivePromptSlots().forEach(slot -> {
                slot.setValue(normalizedAnswer);
                slot.setAnswered(true);
            });
        }
        session.setClarifiedInstruction(buildClarifiedInstruction(session, normalizedAnswer));
        return session;
    }

    private String buildClarifiedInstruction(PlanTaskSession session, String answer) {
        String base = firstNonBlank(session.getClarifiedInstruction(), session.getRawInstruction());
        if (base == null || base.isBlank()) {
            return answer;
        }
        if (answer == null || answer.isBlank() || base.contains(answer)) {
            return base;
        }
        return base + "\n补充说明：" + answer;
    }

    private List<PromptSlotState> toPromptSlots(List<String> questions) {
        List<PromptSlotState> slots = new ArrayList<>();
        int index = 1;
        for (String question : questions) {
            slots.add(PromptSlotState.builder()
                    .slotKey("clarification-" + index++)
                    .prompt(question)
                    .value("")
                    .answered(false)
                    .build());
        }
        return slots;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
