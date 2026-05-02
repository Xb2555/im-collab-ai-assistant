package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.facade.ImTaskCommandFacade;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class PlannerExecutionTool {

    private final ObjectProvider<ImTaskCommandFacade> commandFacadeProvider;

    public PlannerExecutionTool(ObjectProvider<ImTaskCommandFacade> commandFacadeProvider) {
        this.commandFacadeProvider = commandFacadeProvider;
    }

    @Tool(description = "Scenario B/C: confirm execution for a ready planner task using the existing execution bridge.")
    public PlannerToolResult confirmExecution(String taskId) {
        ImTaskCommandFacade commandFacade = commandFacadeProvider.getIfAvailable();
        if (commandFacade == null) {
            return PlannerToolResult.failure(taskId, null, "执行桥接尚未就绪，无法从 Planner 直接启动任务。");
        }
        commandFacade.confirmExecution(taskId);
        TaskRuntimeSnapshot snapshot = commandFacade.getRuntimeSnapshot(taskId);
        return PlannerToolResult.success(
                taskId,
                resolvePhase(snapshot),
                "execution confirmed",
                snapshot
        );
    }

    private PlanningPhaseEnum resolvePhase(TaskRuntimeSnapshot snapshot) {
        if (snapshot == null || snapshot.getTask() == null || snapshot.getTask().getCurrentStage() == null) {
            return PlanningPhaseEnum.EXECUTING;
        }
        try {
            return PlanningPhaseEnum.valueOf(snapshot.getTask().getCurrentStage());
        } catch (IllegalArgumentException ignored) {
            return PlanningPhaseEnum.EXECUTING;
        }
    }
}
