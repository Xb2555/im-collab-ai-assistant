package com.lark.imcollab.harness.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.harness.document.service.DocumentExecutionService;
import com.lark.imcollab.harness.presentation.service.PresentationExecutionService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExecutionTaskDispatcher {

    private final DocumentExecutionService documentExecutionService;
    private final PresentationExecutionService presentationExecutionService;

    public ExecutionTaskDispatcher(
            DocumentExecutionService documentExecutionService,
            PresentationExecutionService presentationExecutionService) {
        this.documentExecutionService = documentExecutionService;
        this.presentationExecutionService = presentationExecutionService;
    }

    public PlanTaskSession dispatch(PlanTaskSession session) {
        List<UserPlanCard> cards = session.getPlanCards() == null ? List.of() : session.getPlanCards();
        PlanTaskSession latest = session;
        for (UserPlanCard card : cards) {
            if (card.getType() == PlanCardTypeEnum.DOC) {
                latest = documentExecutionService.execute(latest.getTaskId(), card.getCardId(), null);
            } else if (card.getType() == PlanCardTypeEnum.PPT) {
                latest = presentationExecutionService.reserveExecution(latest.getTaskId(), card.getCardId());
            }
        }
        return latest;
    }

    public PlanTaskSession resume(String taskId, String userFeedback) {
        return documentExecutionService.resume(taskId, userFeedback);
    }

    public PlanTaskSession interrupt(String taskId) {
        return documentExecutionService.interrupt(taskId);
    }
}
