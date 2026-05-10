package com.lark.imcollab.common.model.vo;

import java.util.List;

public record TaskDetailVO(
        TaskSummaryVO task,
        List<TaskStepVO> steps,
        List<TaskArtifactVO> artifacts,
        List<TaskEventVO> events,
        TaskActionVO actions,
        TaskEvaluationVO evaluation
) {
    public TaskDetailVO(
            TaskSummaryVO task,
            List<TaskStepVO> steps,
            List<TaskArtifactVO> artifacts,
            List<TaskEventVO> events,
            TaskActionVO actions
    ) {
        this(task, steps, artifacts, events, actions, null);
    }
}
