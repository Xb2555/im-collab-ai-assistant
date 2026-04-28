package com.lark.imcollab.planner.prompt;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PromptSessionContext {

    private final PlannerSessionService plannerSessionService;

    public PromptSessionContext(PlannerSessionService plannerSessionService) {
        this.plannerSessionService = plannerSessionService;
    }

    public Optional<PlanTaskSession> get(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(plannerSessionService.get(taskId));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
