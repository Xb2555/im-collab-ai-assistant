package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.planner.clarification.ClarificationService;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.springframework.stereotype.Service;

@Service
public class ClarificationNodeService {

    private final PlannerSessionService sessionService;
    private final ClarificationService clarificationService;
    private final PlannerConversationMemoryService memoryService;
    private final PlanningNodeService planningNodeService;

    public ClarificationNodeService(
            PlannerSessionService sessionService,
            ClarificationService clarificationService,
            PlannerConversationMemoryService memoryService,
            PlanningNodeService planningNodeService
    ) {
        this.sessionService = sessionService;
        this.clarificationService = clarificationService;
        this.memoryService = memoryService;
        this.planningNodeService = planningNodeService;
    }

    public PlanTaskSession resume(String taskId, String feedback) {
        return resume(taskId, feedback, null);
    }

    public PlanTaskSession resume(String taskId, String feedback, WorkspaceContext workspaceContext) {
        PlanTaskSession session = sessionService.get(taskId);
        clarificationService.absorbAnswer(session, feedback);
        session.setAborted(false);
        session.setTransitionReason("Resume: " + feedback);
        memoryService.appendUserTurnIfLatestDifferent(session, feedback, null, "CLARIFICATION_REPLY");
        sessionService.saveWithoutVersionChange(session);
        sessionService.publishEvent(taskId, "RESUMED");
        return planningNodeService.plan(taskId, effectiveInstruction(session, feedback), workspaceContext, feedback);
    }

    private String effectiveInstruction(PlanTaskSession session, String fallback) {
        if (session == null) {
            return fallback;
        }
        return firstNonBlank(session.getClarifiedInstruction(), session.getRawInstruction(), fallback);
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
