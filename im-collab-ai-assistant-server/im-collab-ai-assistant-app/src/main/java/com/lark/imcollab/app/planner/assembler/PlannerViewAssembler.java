package com.lark.imcollab.app.planner.assembler;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.vo.PlanCardVO;
import com.lark.imcollab.common.model.vo.PlanPreviewVO;
import com.lark.imcollab.common.model.vo.TaskActionVO;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlannerViewAssembler {

    public PlanPreviewVO toPlanPreview(PlanTaskSession session) {
        if (session == null) {
            return null;
        }
        return new PlanPreviewVO(
                session.getTaskId(),
                enumName(session.getPlanningPhase()),
                resolveTitle(session),
                session.getPlanBlueprintSummary(),
                toPlanCards(session.getPlanCards()),
                defaultList(session.getClarificationQuestions()),
                defaultList(session.getClarificationAnswers()),
                resolveActions(session.getPlanningPhase(), session.isAborted())
        );
    }

    public List<PlanCardVO> toPlanCards(List<UserPlanCard> cards) {
        return defaultList(cards).stream()
                .map(this::toPlanCard)
                .toList();
    }

    private PlanCardVO toPlanCard(UserPlanCard card) {
        return new PlanCardVO(
                card.getCardId(),
                card.getTitle(),
                card.getDescription(),
                card.getType() == null ? null : card.getType().name(),
                card.getStatus(),
                card.getProgress(),
                defaultList(card.getDependsOn())
        );
    }

    private TaskActionVO resolveActions(PlanningPhaseEnum phase, boolean aborted) {
        if (aborted || phase == PlanningPhaseEnum.ABORTED || phase == PlanningPhaseEnum.COMPLETED) {
            return new TaskActionVO(false, false, false, false, false);
        }
        boolean canConfirm = phase == PlanningPhaseEnum.PLAN_READY;
        boolean canReplan = phase == PlanningPhaseEnum.PLAN_READY
                || phase == PlanningPhaseEnum.EXECUTING
                || phase == PlanningPhaseEnum.FAILED;
        boolean canCancel = phase != PlanningPhaseEnum.COMPLETED && phase != PlanningPhaseEnum.ABORTED;
        boolean canResume = phase == PlanningPhaseEnum.ASK_USER || phase == PlanningPhaseEnum.FAILED;
        boolean canInterrupt = phase == PlanningPhaseEnum.EXECUTING;
        return new TaskActionVO(canConfirm, canReplan, canCancel, canResume, canInterrupt);
    }

    private String resolveTitle(PlanTaskSession session) {
        List<UserPlanCard> cards = session.getPlanCards();
        if (cards != null && !cards.isEmpty() && cards.get(0) != null && cards.get(0).getTitle() != null) {
            return cards.get(0).getTitle();
        }
        return session.getPlanBlueprintSummary();
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
