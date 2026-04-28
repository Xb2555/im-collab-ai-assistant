package com.lark.imcollab.harness.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.harness.scene.c.service.SceneCExecutionService;
import com.lark.imcollab.harness.scene.d.service.SceneDExecutionService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SceneTaskDispatcher {

    private final SceneCExecutionService sceneCExecutionService;
    private final SceneDExecutionService sceneDExecutionService;

    public SceneTaskDispatcher(
            SceneCExecutionService sceneCExecutionService,
            SceneDExecutionService sceneDExecutionService) {
        this.sceneCExecutionService = sceneCExecutionService;
        this.sceneDExecutionService = sceneDExecutionService;
    }

    public PlanTaskSession dispatch(PlanTaskSession session) {
        List<UserPlanCard> cards = session.getPlanCards() == null ? List.of() : session.getPlanCards();
        PlanTaskSession latest = session;
        for (UserPlanCard card : cards) {
            if (card.getType() == PlanCardTypeEnum.DOC) {
                latest = sceneCExecutionService.execute(latest.getTaskId(), card.getCardId(), null);
            } else if (card.getType() == PlanCardTypeEnum.PPT) {
                latest = sceneDExecutionService.reserveExecution(latest.getTaskId(), card.getCardId());
            }
        }
        return latest;
    }

    public PlanTaskSession resume(String taskId, String userFeedback) {
        return sceneCExecutionService.resume(taskId, userFeedback);
    }

    public PlanTaskSession interrupt(String taskId) {
        return sceneCExecutionService.interrupt(taskId);
    }
}
