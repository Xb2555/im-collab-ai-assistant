package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;
import com.lark.imcollab.planner.config.PlannerProperties;

import java.util.Comparator;
import java.util.List;

final class RoutingPolicyEngine {

    private final RoutingEvidenceExtractor routingEvidenceExtractor;
    private final RoutingTuning tuning;

    RoutingPolicyEngine() {
        this(RoutingTuning.defaults());
    }

    RoutingPolicyEngine(PlannerProperties properties) {
        this(RoutingTuning.from(properties));
    }

    RoutingPolicyEngine(RoutingTuning tuning) {
        this.tuning = tuning == null ? RoutingTuning.defaults() : tuning;
        this.routingEvidenceExtractor = new RoutingEvidenceExtractor(this.tuning);
    }

    CurrentTaskContinuationArbiter.Decision decide(
            boolean upstreamSuggestsStandaloneTask,
            boolean upstreamSuggestsContinuation,
            boolean explicitCurrentTaskContext,
            PendingFollowUpRecommendationMatcher.CarryForwardHint hint,
            PendingFollowUpRecommendationMatcher.MatchResult match,
            RoutingEvidence evidence,
            List<PendingFollowUpRecommendation> recommendations,
            List<PendingFollowUpRecommendation> targetCandidates,
            PendingFollowUpRecommendationMatcher matcher
    ) {
        PendingFollowUpRecommendationMatcher.CarryForwardHint safeHint =
                hint == null ? PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED : hint;
        PendingFollowUpRecommendationMatcher.MatchResult safeMatch =
                match == null ? PendingFollowUpRecommendationMatcher.MatchResult.none() : match;
        RoutingEvidence safeEvidence = evidence == null ? RoutingEvidence.empty(null) : evidence;
        List<PendingFollowUpRecommendation> safeRecommendations =
                recommendations == null ? List.of() : recommendations;
        List<PendingFollowUpRecommendation> safeTargetCandidates =
                targetCandidates == null ? List.of() : targetCandidates;
        RankedRecommendations ranked = rankRecommendations(safeEvidence, safeRecommendations);

        if (safeHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.EXPLICIT_NEW_TASK
                || safeEvidence.freshTaskLevel() == SignalLevel.HIGH) {
            return proceedNewTask(safeHint, PendingFollowUpRecommendationMatcher.MatchResult.none(),
                    safeEvidence, explicitCurrentTaskContext, safeTargetCandidates, "explicit fresh task", ranked);
        }

        int continueCurrentTaskScore = computeContinueCurrentTaskScore(safeEvidence, ranked, explicitCurrentTaskContext);
        int startNewTaskScore = computeStartNewTaskScore(safeEvidence);
        int artifactEditPreferenceScore = computeArtifactEditPreferenceScore(safeEvidence);

        if (safeMatch.type() == PendingFollowUpRecommendationMatcher.Type.ASK_SELECTION
                || safeHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.RELATED_BUT_AMBIGUOUS) {
            return askCurrentTaskSelection(safeHint, safeMatch, safeEvidence, explicitCurrentTaskContext,
                    selectionCandidates(safeRecommendations, safeTargetCandidates),
                    "multiple related current-task candidates", ranked);
        }

        if (safeMatch.type() == PendingFollowUpRecommendationMatcher.Type.SELECTED && safeMatch.recommendation() != null) {
            if (safeHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.EXACT_OR_PREFIX_MATCH) {
                return proceedCurrentTask(safeHint, safeMatch, safeEvidence, explicitCurrentTaskContext,
                        safeTargetCandidates, "exact carry-forward", ranked);
            }
            if (safeHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.SEMANTIC_MATCH_WORTH_LLM
                    && ranked.topScore() >= tuning.proceedCurrentTaskMinScore()
                    && (safeRecommendations.size() == 1 || explicitCurrentTaskContext)) {
                return proceedCurrentTask(safeHint, safeMatch, safeEvidence, explicitCurrentTaskContext, safeTargetCandidates,
                        "single recommendation semantic continuation", ranked);
            }
            if (safeHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.SEMANTIC_MATCH_WORTH_LLM
                    && ranked.topScore() >= tuning.proceedCurrentTaskMinScore()
                    && (safeEvidence.currentTaskReferenceLevel().ordinal() >= SignalLevel.MEDIUM.ordinal()
                    || safeEvidence.continuationIntentLevel().ordinal() >= SignalLevel.MEDIUM.ordinal()
                    || explicitCurrentTaskContext)) {
                return proceedCurrentTask(safeHint, safeMatch, safeEvidence, explicitCurrentTaskContext, safeTargetCandidates,
                        "unique semantic current-task continuation", ranked);
            }
            if (upstreamSuggestsContinuation) {
                return proceedCurrentTask(safeHint, safeMatch, safeEvidence, explicitCurrentTaskContext, safeTargetCandidates,
                        "upstream continuation selected recommendation", ranked);
            }
        }

        if (ranked.topScore() >= tuning.proceedCurrentTaskMinScore()
                && ranked.topRecommendation() != null
                && (safeEvidence.currentTaskReferenceLevel().ordinal() >= SignalLevel.MEDIUM.ordinal()
                || safeEvidence.continuationIntentLevel().ordinal() >= SignalLevel.LOW.ordinal()
                || safeRecommendations.size() == 1
                || explicitCurrentTaskContext)) {
            return proceedCurrentTask(
                    safeHint,
                    PendingFollowUpRecommendationMatcher.MatchResult.selected(ranked.topRecommendation()),
                    safeEvidence,
                    explicitCurrentTaskContext,
                    safeTargetCandidates.isEmpty() ? List.of(ranked.topRecommendation()) : safeTargetCandidates,
                    "policy-selected current-task continuation",
                    ranked
            );
        }

        if (safeEvidence.newDeliverableLevel().ordinal() >= SignalLevel.MEDIUM.ordinal()
                && !explicitCurrentTaskContext
                && safeEvidence.currentTaskReferenceLevel() == SignalLevel.NONE
                && safeEvidence.continuationIntentLevel().ordinal() <= SignalLevel.LOW.ordinal()) {
            return proceedNewTask(safeHint, safeMatch, safeEvidence, explicitCurrentTaskContext, safeTargetCandidates,
                    "new deliverable request without current-task signal", ranked);
        }

        if (continueCurrentTaskScore < tuning.continueCurrentTaskMinScore()
                && startNewTaskScore >= tuning.proceedNewTaskMinScore()) {
            return proceedNewTask(safeHint, safeMatch, safeEvidence, explicitCurrentTaskContext, safeTargetCandidates,
                    "new task score dominates continuation score", ranked);
        }

        if ((safeEvidence.continuationIntentLevel().ordinal() >= SignalLevel.MEDIUM.ordinal()
                || safeEvidence.currentTaskReferenceLevel().ordinal() >= SignalLevel.MEDIUM.ordinal())
                && safeEvidence.freshTaskLevel() != SignalLevel.HIGH) {
            List<PendingFollowUpRecommendation> candidates = selectionCandidates(safeRecommendations, safeTargetCandidates);
            return askNewOrCurrent(safeHint, safeMatch, safeEvidence, explicitCurrentTaskContext, candidates,
                    "continuation signal without decisive recommendation match", ranked);
        }

        if (safeEvidence.ambiguousMaterialOrganizationLevel().ordinal() >= SignalLevel.MEDIUM.ordinal()
                && !safeRecommendations.isEmpty()
                && safeEvidence.freshTaskLevel() != SignalLevel.HIGH) {
            List<PendingFollowUpRecommendation> candidates = selectionCandidates(safeRecommendations, safeTargetCandidates);
            return askNewOrCurrent(safeHint, safeMatch, safeEvidence, explicitCurrentTaskContext, candidates,
                    "ambiguous material organization request", ranked);
        }

        if (artifactEditPreferenceScore >= tuning.artifactEditPreferenceMinScore()) {
            return bypassCompletedArtifact("artifact edit preference dominates");
        }

        if (upstreamSuggestsStandaloneTask) {
            return proceedNewTask(safeHint, safeMatch, safeEvidence, explicitCurrentTaskContext, safeTargetCandidates,
                    "standalone task has no current-task signal", ranked);
        }
        return noDecision("no current-task arbitration decision", ranked);
    }

    private int computeContinueCurrentTaskScore(
            RoutingEvidence evidence,
            RankedRecommendations ranked,
            boolean explicitCurrentTaskContext
    ) {
        int score = 0;
        if (evidence.currentTaskReferenceLevel() == SignalLevel.HIGH) {
            score += 35;
        } else if (evidence.currentTaskReferenceLevel() == SignalLevel.MEDIUM) {
            score += 20;
        }
        if (evidence.continuationIntentLevel() == SignalLevel.HIGH) {
            score += 35;
        } else if (evidence.continuationIntentLevel() == SignalLevel.MEDIUM) {
            score += 20;
        }
        score += Math.min(ranked.topScore(), 30);
        if (explicitCurrentTaskContext) {
            score += 25;
        }
        return Math.min(score, 100);
    }

    private int computeStartNewTaskScore(RoutingEvidence evidence) {
        int score = evidence.freshTaskScore();
        if (evidence.newDeliverableLevel() == SignalLevel.HIGH) {
            score += 20;
        } else if (evidence.newDeliverableLevel() == SignalLevel.MEDIUM) {
            score += 10;
        }
        if (evidence.currentTaskReferenceLevel().ordinal() >= SignalLevel.MEDIUM.ordinal()) {
            score -= 20;
        }
        if (evidence.continuationIntentLevel().ordinal() >= SignalLevel.MEDIUM.ordinal()) {
            score -= 15;
        }
        return clamp(score);
    }

    private int computeArtifactEditPreferenceScore(RoutingEvidence evidence) {
        int score = evidence.artifactEditScore();
        if (evidence.ambiguousMaterialOrganizationLevel().ordinal() >= SignalLevel.MEDIUM.ordinal()) {
            score -= 40;
        }
        if (evidence.newDeliverableLevel().ordinal() >= SignalLevel.MEDIUM.ordinal()) {
            score -= 30;
        }
        if (evidence.freshTaskLevel() == SignalLevel.HIGH) {
            score -= 50;
        }
        return clamp(score);
    }

    private List<PendingFollowUpRecommendation> selectionCandidates(
            List<PendingFollowUpRecommendation> recommendations,
            List<PendingFollowUpRecommendation> targetCandidates
    ) {
        return targetCandidates == null || targetCandidates.isEmpty()
                ? (recommendations == null ? List.of() : recommendations)
                : targetCandidates;
    }

    private RankedRecommendations rankRecommendations(
            RoutingEvidence evidence,
            List<PendingFollowUpRecommendation> recommendations
    ) {
        if (evidence == null || recommendations == null || recommendations.isEmpty()) {
            return RankedRecommendations.empty();
        }
        List<RankedRecommendation> ranked = recommendations.stream()
                .filter(recommendation -> recommendation != null)
                .map(recommendation -> new RankedRecommendation(
                        recommendation,
                        routingEvidenceExtractor.scoreRecommendationAffinity(evidence, recommendation)
                ))
                .sorted(Comparator.comparingInt(RankedRecommendation::score).reversed())
                .toList();
        if (ranked.isEmpty()) {
            return RankedRecommendations.empty();
        }
        RankedRecommendation top = ranked.get(0);
        RankedRecommendation second = ranked.size() > 1 ? ranked.get(1) : null;
        return new RankedRecommendations(top, second);
    }

    private CurrentTaskContinuationArbiter.Decision noDecision(String reason, RankedRecommendations ranked) {
        return new CurrentTaskContinuationArbiter.Decision(
                CurrentTaskContinuationArbiter.DecisionType.NO_DECISION,
                PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED,
                PendingFollowUpRecommendationMatcher.MatchResult.none(),
                null,
                false,
                List.of(),
                reason,
                ranked.topRecommendationId(),
                ranked.topScore(),
                ranked.secondRecommendationId(),
                ranked.secondScore()
        );
    }

    private CurrentTaskContinuationArbiter.Decision proceedNewTask(
            PendingFollowUpRecommendationMatcher.CarryForwardHint hint,
            PendingFollowUpRecommendationMatcher.MatchResult match,
            RoutingEvidence evidence,
            boolean explicitCurrentTaskContext,
            List<PendingFollowUpRecommendation> candidates,
            String reason,
            RankedRecommendations ranked
    ) {
        return new CurrentTaskContinuationArbiter.Decision(
                CurrentTaskContinuationArbiter.DecisionType.PROCEED_NEW_TASK,
                hint,
                match,
                null,
                explicitCurrentTaskContext || evidence.currentTaskReferenceLevel().ordinal() >= SignalLevel.MEDIUM.ordinal(),
                candidates == null ? List.of() : candidates,
                reason,
                ranked.topRecommendationId(),
                ranked.topScore(),
                ranked.secondRecommendationId(),
                ranked.secondScore()
        );
    }

    private CurrentTaskContinuationArbiter.Decision proceedCurrentTask(
            PendingFollowUpRecommendationMatcher.CarryForwardHint hint,
            PendingFollowUpRecommendationMatcher.MatchResult match,
            RoutingEvidence evidence,
            boolean explicitCurrentTaskContext,
            List<PendingFollowUpRecommendation> candidates,
            String reason,
            RankedRecommendations ranked
    ) {
        return new CurrentTaskContinuationArbiter.Decision(
                CurrentTaskContinuationArbiter.DecisionType.PROCEED_CURRENT_TASK,
                hint,
                match,
                match == null ? null : match.recommendation(),
                explicitCurrentTaskContext || evidence.currentTaskReferenceLevel().ordinal() >= SignalLevel.MEDIUM.ordinal(),
                candidates == null ? List.of() : candidates,
                reason,
                ranked.topRecommendationId(),
                ranked.topScore(),
                ranked.secondRecommendationId(),
                ranked.secondScore()
        );
    }

    private CurrentTaskContinuationArbiter.Decision askNewOrCurrent(
            PendingFollowUpRecommendationMatcher.CarryForwardHint hint,
            PendingFollowUpRecommendationMatcher.MatchResult match,
            RoutingEvidence evidence,
            boolean explicitCurrentTaskContext,
            List<PendingFollowUpRecommendation> candidates,
            String reason,
            RankedRecommendations ranked
    ) {
        List<PendingFollowUpRecommendation> safeCandidates = candidates == null ? List.of() : candidates;
        PendingFollowUpRecommendation selected = safeCandidates.size() == 1 ? safeCandidates.get(0) : null;
        return new CurrentTaskContinuationArbiter.Decision(
                CurrentTaskContinuationArbiter.DecisionType.ASK_NEW_OR_CURRENT,
                hint,
                match,
                selected,
                explicitCurrentTaskContext || evidence.currentTaskReferenceLevel().ordinal() >= SignalLevel.MEDIUM.ordinal(),
                safeCandidates,
                reason,
                ranked.topRecommendationId(),
                ranked.topScore(),
                ranked.secondRecommendationId(),
                ranked.secondScore()
        );
    }

    private CurrentTaskContinuationArbiter.Decision askCurrentTaskSelection(
            PendingFollowUpRecommendationMatcher.CarryForwardHint hint,
            PendingFollowUpRecommendationMatcher.MatchResult match,
            RoutingEvidence evidence,
            boolean explicitCurrentTaskContext,
            List<PendingFollowUpRecommendation> candidates,
            String reason,
            RankedRecommendations ranked
    ) {
        return new CurrentTaskContinuationArbiter.Decision(
                CurrentTaskContinuationArbiter.DecisionType.ASK_CURRENT_TASK_SELECTION,
                hint,
                match,
                null,
                explicitCurrentTaskContext || evidence.currentTaskReferenceLevel().ordinal() >= SignalLevel.MEDIUM.ordinal(),
                candidates == null ? List.of() : candidates,
                reason,
                ranked.topRecommendationId(),
                ranked.topScore(),
                ranked.secondRecommendationId(),
                ranked.secondScore()
        );
    }

    private CurrentTaskContinuationArbiter.Decision bypassCompletedArtifact(String reason) {
        return new CurrentTaskContinuationArbiter.Decision(
                CurrentTaskContinuationArbiter.DecisionType.BYPASS_TO_COMPLETED_ARTIFACT_EDIT,
                PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED,
                PendingFollowUpRecommendationMatcher.MatchResult.none(),
                null,
                true,
                List.of(),
                reason,
                null,
                0,
                null,
                0
        );
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private record RankedRecommendation(
            PendingFollowUpRecommendation recommendation,
            int score
    ) {
    }

    private record RankedRecommendations(
            RankedRecommendation top,
            RankedRecommendation second
    ) {
        static RankedRecommendations empty() {
            return new RankedRecommendations(null, null);
        }

        int topScore() {
            return top == null ? 0 : top.score();
        }

        int secondScore() {
            return second == null ? 0 : second.score();
        }

        PendingFollowUpRecommendation topRecommendation() {
            return top == null ? null : top.recommendation();
        }

        String topRecommendationId() {
            PendingFollowUpRecommendation recommendation = topRecommendation();
            return recommendation == null ? null : recommendation.getRecommendationId();
        }

        String secondRecommendationId() {
            PendingFollowUpRecommendation recommendation = second == null ? null : second.recommendation();
            return recommendation == null ? null : recommendation.getRecommendationId();
        }

        boolean hasAmbiguousTopCandidates() {
            return topScore() >= 50 && secondScore() >= 50 && Math.abs(topScore() - secondScore()) < 25;
        }
    }
}
