package com.lark.imcollab.common.model.vo;

import java.util.List;

public record TaskEvaluationVO(
        String verdict,
        List<String> suggestions,
        List<NextStepRecommendationVO> nextStepRecommendations
) {
}
