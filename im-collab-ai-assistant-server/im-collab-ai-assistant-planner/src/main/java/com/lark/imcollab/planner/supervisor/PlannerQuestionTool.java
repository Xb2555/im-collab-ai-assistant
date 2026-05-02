package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.planner.clarification.ClarificationService;
import com.lark.imcollab.planner.runtime.TaskRuntimeProjectionService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlannerQuestionTool {

    private final ClarificationService clarificationService;
    private final PlannerSessionService sessionService;
    private final TaskRuntimeProjectionService projectionService;

    public PlannerQuestionTool(
            ClarificationService clarificationService,
            PlannerSessionService sessionService,
            TaskRuntimeProjectionService projectionService
    ) {
        this.clarificationService = clarificationService;
        this.sessionService = sessionService;
        this.projectionService = projectionService;
    }

    @Tool(description = "Scenario B: ask the user one to three planner clarification questions and pause the task.")
    public PlannerToolResult askUser(PlanTaskSession session, List<String> questions) {
        if (session == null) {
            return PlannerToolResult.failure(null, null, "任务不存在，无法发起澄清。");
        }
        List<String> safeQuestions = questions == null ? List.of() : questions.stream()
                .filter(question -> question != null && !question.isBlank())
                .map(String::trim)
                .distinct()
                .limit(3)
                .toList();
        if (safeQuestions.isEmpty()) {
            safeQuestions = List.of("我还需要确认一下：你希望我基于哪些内容，输出文档、PPT 还是摘要？");
        }
        clarificationService.askUser(session, safeQuestions);
        sessionService.save(session);
        projectionService.projectStage(session, TaskEventTypeEnum.CLARIFICATION_REQUIRED, safeQuestions);
        RequireInput requireInput = RequireInput.builder()
                .type("TEXT")
                .prompt(String.join("\n", safeQuestions))
                .build();
        sessionService.publishEvent(session.getTaskId(), "ASK_USER", requireInput);
        return PlannerToolResult.success(session.getTaskId(), session.getPlanningPhase(), "clarification required", safeQuestions);
    }
}
