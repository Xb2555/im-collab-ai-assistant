package com.lark.imcollab.common.model.vo;

import java.util.List;

public record PlanPreviewVO(
        String taskId,
        String planningPhase,
        String title,
        String summary,
        List<PlanCardVO> cards,
        List<String> clarificationQuestions,
        List<String> clarificationAnswers,
        TaskActionVO actions
) {
}
