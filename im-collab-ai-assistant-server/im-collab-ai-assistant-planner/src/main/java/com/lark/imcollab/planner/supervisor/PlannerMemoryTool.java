package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class PlannerMemoryTool {

    private final PlannerConversationMemoryService memoryService;

    public PlannerMemoryTool(PlannerConversationMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool(description = "Scenario B: append a user message to task-scoped planner memory.")
    public PlannerToolResult appendUserTurn(
            PlanTaskSession session,
            String content,
            TaskIntakeTypeEnum intakeType,
            String source
    ) {
        memoryService.appendUserTurnIfLatestDifferent(session, content, intakeType, source);
        return PlannerToolResult.success(
                session == null ? null : session.getTaskId(),
                session == null ? null : session.getPlanningPhase(),
                "user memory appended",
                null
        );
    }

    @Tool(description = "Scenario B: append an assistant semantic message to task-scoped planner memory.")
    public PlannerToolResult appendAssistantTurn(PlanTaskSession session, String content) {
        memoryService.appendAssistantTurn(session, content);
        return PlannerToolResult.success(
                session == null ? null : session.getTaskId(),
                session == null ? null : session.getPlanningPhase(),
                "assistant memory appended",
                null
        );
    }

    @Tool(description = "Scenario B: render short task-scoped planner memory for an agent prompt.")
    public String renderContext(PlanTaskSession session) {
        return memoryService.renderContext(session);
    }
}
