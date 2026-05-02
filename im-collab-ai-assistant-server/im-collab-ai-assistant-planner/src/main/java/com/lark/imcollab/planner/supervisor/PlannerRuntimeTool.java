package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.planner.runtime.TaskRuntimeProjectionService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class PlannerRuntimeTool {

    private final TaskRuntimeProjectionService projectionService;
    private final PlannerSessionService sessionService;

    public PlannerRuntimeTool(TaskRuntimeProjectionService projectionService, PlannerSessionService sessionService) {
        this.projectionService = projectionService;
        this.sessionService = sessionService;
    }

    @Tool(description = "Scenario B/E: query task runtime snapshot without triggering replan.")
    public TaskRuntimeSnapshot getSnapshot(String taskId) {
        return projectionService.getSnapshot(taskId);
    }

    @Tool(description = "Scenario B/E: append a planner stage event to the runtime timeline.")
    public PlannerToolResult projectStage(String taskId, TaskEventTypeEnum eventType, String message) {
        projectionService.projectStage(sessionService.get(taskId), eventType, message);
        TaskRuntimeSnapshot updated = projectionService.getSnapshot(taskId);
        return PlannerToolResult.success(taskId,
                resolvePhase(updated),
                "runtime stage projected",
                updated);
    }

    private PlanningPhaseEnum resolvePhase(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null || snapshot.getTask() == null || snapshot.getTask().getCurrentStage() == null) {
            return null;
        }
        try {
            return PlanningPhaseEnum.valueOf(snapshot.getTask().getCurrentStage());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
