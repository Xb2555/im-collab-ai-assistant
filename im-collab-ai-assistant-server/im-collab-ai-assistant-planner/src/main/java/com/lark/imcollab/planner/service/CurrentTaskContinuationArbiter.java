package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;

import java.util.List;

public class CurrentTaskContinuationArbiter {

    protected final PendingFollowUpRecommendationMatcher matcher;
    private final RoutingEvidenceExtractor routingEvidenceExtractor = new RoutingEvidenceExtractor();
    private final RoutingPolicyEngine routingPolicyEngine = new RoutingPolicyEngine();

    public CurrentTaskContinuationArbiter(PendingFollowUpRecommendationMatcher matcher) {
        this.matcher = matcher;
    }

    public Decision arbitratePreview(
            TaskCommandTypeEnum upstreamType,
            String userInput,
            List<PendingFollowUpRecommendation> recommendations,
            boolean awaitingSelection,
            CompletedArtifactIntentRecoveryService.DirectRouteEvaluation directRouteEvaluation
    ) {
        return arbitrate(
                upstreamType == TaskCommandTypeEnum.START_TASK,
                upstreamType == TaskCommandTypeEnum.ADJUST_PLAN || upstreamType == TaskCommandTypeEnum.CONFIRM_ACTION,
                userInput,
                recommendations,
                awaitingSelection,
                directRouteEvaluation
        );
    }

    public Decision arbitrateExecution(
            TaskIntakeTypeEnum upstreamType,
            String userInput,
            List<PendingFollowUpRecommendation> recommendations,
            boolean awaitingSelection,
            CompletedArtifactIntentRecoveryService.DirectRouteEvaluation directRouteEvaluation
    ) {
        return arbitrate(
                upstreamType == TaskIntakeTypeEnum.NEW_TASK,
                upstreamType == TaskIntakeTypeEnum.PLAN_ADJUSTMENT || upstreamType == TaskIntakeTypeEnum.CONFIRM_ACTION,
                userInput,
                recommendations,
                awaitingSelection,
                directRouteEvaluation
        );
    }

    private Decision arbitrate(
            boolean upstreamSuggestsStandaloneTask,
            boolean upstreamSuggestsContinuation,
            String userInput,
            List<PendingFollowUpRecommendation> recommendations,
            boolean awaitingSelection,
            CompletedArtifactIntentRecoveryService.DirectRouteEvaluation directRouteEvaluation
    ) {
        if (directRouteEvaluation != null
                && directRouteEvaluation.type() != CompletedArtifactIntentRecoveryService.DirectRouteType.NONE) {
            return Decision.bypassCompletedArtifact("completed artifact direct route");
        }
        if (matcher == null || !hasText(userInput)) {
            return Decision.noDecision("matcher unavailable or blank input");
        }

        List<PendingFollowUpRecommendation> safeRecommendations = recommendations == null ? List.of() : recommendations;
        PendingFollowUpRecommendationMatcher.CarryForwardHint hint = matcher.classifyCarryForwardCandidate(userInput, safeRecommendations);
        if (hint == null) {
            hint = PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED;
        }
        RoutingEvidence evidence = routingEvidenceExtractor.extract(userInput);
        List<PendingFollowUpRecommendation> targetCandidates = targetOnlyCandidates(evidence, safeRecommendations);
        if (hint == PendingFollowUpRecommendationMatcher.CarryForwardHint.EXPLICIT_NEW_TASK) {
            return routingPolicyEngine.decide(
                    upstreamSuggestsStandaloneTask,
                    upstreamSuggestsContinuation,
                    hint,
                    PendingFollowUpRecommendationMatcher.MatchResult.none(),
                    evidence,
                    safeRecommendations,
                    targetCandidates
            );
        }
        if (upstreamSuggestsStandaloneTask
                && hint == PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED
                && !evidence.currentTaskReference()
                && !evidence.continuationSignal()) {
            return routingPolicyEngine.decide(
                    upstreamSuggestsStandaloneTask,
                    upstreamSuggestsContinuation,
                    hint,
                    PendingFollowUpRecommendationMatcher.MatchResult.none(),
                    evidence,
                    safeRecommendations,
                    targetCandidates
            );
        }

        PendingFollowUpRecommendationMatcher.MatchResult match = safeRecommendations.isEmpty()
                ? PendingFollowUpRecommendationMatcher.MatchResult.none()
                : matcher.match(userInput, safeRecommendations, awaitingSelection, upstreamSuggestsStandaloneTask);
        return routingPolicyEngine.decide(
                upstreamSuggestsStandaloneTask,
                upstreamSuggestsContinuation,
                hint,
                match,
                evidence,
                safeRecommendations,
                targetCandidates
        );
    }

    private List<PendingFollowUpRecommendation> targetOnlyCandidates(
            String userInput,
            List<PendingFollowUpRecommendation> recommendations
    ) {
        return targetOnlyCandidates(routingEvidenceExtractor.extract(userInput), recommendations);
    }

    private List<PendingFollowUpRecommendation> targetOnlyCandidates(
            RoutingEvidence evidence,
            List<PendingFollowUpRecommendation> recommendations
    ) {
        if (evidence == null || recommendations == null || recommendations.isEmpty()) {
            return List.of();
        }
        return routingEvidenceExtractor.targetCandidates(evidence, recommendations);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Decision(
            DecisionType type,
            PendingFollowUpRecommendationMatcher.CarryForwardHint hint,
            PendingFollowUpRecommendationMatcher.MatchResult match,
            PendingFollowUpRecommendation selectedRecommendation,
            boolean currentReference,
            List<PendingFollowUpRecommendation> candidateRecommendations,
            String reason
    ) {
        static Decision bypassCompletedArtifact(String reason) {
            return new Decision(DecisionType.BYPASS_TO_COMPLETED_ARTIFACT_EDIT,
                    PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED,
                    PendingFollowUpRecommendationMatcher.MatchResult.none(), null, true, List.of(), reason);
        }

        static Decision noDecision(String reason) {
            return new Decision(DecisionType.NO_DECISION,
                    PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED,
                    PendingFollowUpRecommendationMatcher.MatchResult.none(), null, false, List.of(), reason);
        }
    }

    public enum DecisionType {
        PROCEED_NEW_TASK,
        PROCEED_CURRENT_TASK,
        ASK_NEW_OR_CURRENT,
        ASK_CURRENT_TASK_SELECTION,
        BYPASS_TO_COMPLETED_ARTIFACT_EDIT,
        NO_DECISION
    }
}
