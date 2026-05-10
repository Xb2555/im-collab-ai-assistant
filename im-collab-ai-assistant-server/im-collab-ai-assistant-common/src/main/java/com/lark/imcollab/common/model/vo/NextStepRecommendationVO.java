package com.lark.imcollab.common.model.vo;

public record NextStepRecommendationVO(
        String code,
        String recommendationId,
        String title,
        String reason,
        String suggestedUserInstruction,
        String targetDeliverable,
        String followUpMode,
        String targetTaskId,
        String sourceArtifactId,
        String sourceArtifactType,
        String plannerInstruction,
        String artifactPolicy,
        int priority
) {
}
