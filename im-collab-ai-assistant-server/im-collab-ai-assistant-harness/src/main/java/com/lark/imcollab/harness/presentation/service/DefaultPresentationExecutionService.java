package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.facade.PlannerRuntimeFacade;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultPresentationExecutionService implements PresentationExecutionService {

    private final PlannerRuntimeFacade plannerRuntimeFacade;

    @Override
    public PlanTaskSession reserveExecution(String taskId, String cardId) {
        PlanTaskSession session = plannerRuntimeFacade.getSession(taskId);
        UserPlanCard card = session.getPlanCards().stream()
                .filter(item -> item.getCardId().equals(cardId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Card not found: " + cardId));
        card.setStatus("BLOCKED");
        card.setProgress(0);
        plannerRuntimeFacade.saveSession(session);
        plannerRuntimeFacade.publishEvent(taskId, "PRESENTATION_SKELETON_RESERVED");
        return session;
    }
}
