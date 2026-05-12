package com.lark.imcollab.planner.service;

import com.lark.imcollab.planner.config.PlannerProperties;

public record RoutingTuning(
        int signalLowUpperBound,
        int signalMediumUpperBound,
        int freshTaskExplicitScore,
        int freshTaskResetScore,
        int currentTaskReferenceStrongScore,
        int currentArtifactReferenceScore,
        int continuationKeywordScore,
        int continuationAudienceScore,
        int continuationIntentCap,
        int artifactEditAnchorScore,
        int artifactEditMutationScore,
        int artifactEditStrongThreshold,
        int newDeliverableActionScore,
        int newDeliverableMentionOnlyScore,
        int newDeliverableMentionWithEditAnchorScore,
        int ambiguousMaterialBaseScore,
        int ambiguousMaterialWithDeliverableScore,
        int affinityDeliverableMediumScore,
        int affinityDeliverableHighScore,
        int affinitySourceMediumScore,
        int affinitySourceHighScore,
        int affinityContinuationMediumScore,
        int affinityContinuationHighScore,
        int affinityCurrentTaskMediumScore,
        int affinityCurrentTaskHighScore,
        int semanticCandidateMinScore,
        int semanticUniqueGap,
        int proceedCurrentTaskMinScore,
        int proceedNewTaskMinScore,
        int continueCurrentTaskMinScore,
        int artifactEditPreferenceMinScore
) {

    public static RoutingTuning defaults() {
        return from(new PlannerProperties());
    }

    public static RoutingTuning from(PlannerProperties properties) {
        PlannerProperties.Routing routing = properties == null ? new PlannerProperties.Routing() : properties.getRouting();
        return new RoutingTuning(
                routing.getSignalLowUpperBound(),
                routing.getSignalMediumUpperBound(),
                routing.getFreshTaskExplicitScore(),
                routing.getFreshTaskResetScore(),
                routing.getCurrentTaskReferenceStrongScore(),
                routing.getCurrentArtifactReferenceScore(),
                routing.getContinuationKeywordScore(),
                routing.getContinuationAudienceScore(),
                routing.getContinuationIntentCap(),
                routing.getArtifactEditAnchorScore(),
                routing.getArtifactEditMutationScore(),
                routing.getArtifactEditStrongThreshold(),
                routing.getNewDeliverableActionScore(),
                routing.getNewDeliverableMentionOnlyScore(),
                routing.getNewDeliverableMentionWithEditAnchorScore(),
                routing.getAmbiguousMaterialBaseScore(),
                routing.getAmbiguousMaterialWithDeliverableScore(),
                routing.getAffinityDeliverableMediumScore(),
                routing.getAffinityDeliverableHighScore(),
                routing.getAffinitySourceMediumScore(),
                routing.getAffinitySourceHighScore(),
                routing.getAffinityContinuationMediumScore(),
                routing.getAffinityContinuationHighScore(),
                routing.getAffinityCurrentTaskMediumScore(),
                routing.getAffinityCurrentTaskHighScore(),
                routing.getSemanticCandidateMinScore(),
                routing.getSemanticUniqueGap(),
                routing.getProceedCurrentTaskMinScore(),
                routing.getProceedNewTaskMinScore(),
                routing.getContinueCurrentTaskMinScore(),
                routing.getArtifactEditPreferenceMinScore()
        );
    }

    public SignalLevel levelOf(int score) {
        if (score <= 0) {
            return SignalLevel.NONE;
        }
        if (score <= signalLowUpperBound) {
            return SignalLevel.LOW;
        }
        if (score <= signalMediumUpperBound) {
            return SignalLevel.MEDIUM;
        }
        return SignalLevel.HIGH;
    }
}
