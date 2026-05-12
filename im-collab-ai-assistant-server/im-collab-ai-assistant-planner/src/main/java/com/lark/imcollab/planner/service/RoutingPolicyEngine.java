package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PendingFollowUpRecommendation;

import java.util.List;

final class RoutingPolicyEngine {

    CurrentTaskContinuationArbiter.Decision decide(
            boolean upstreamSuggestsStandaloneTask,
            boolean upstreamSuggestsContinuation,
            PendingFollowUpRecommendationMatcher.CarryForwardHint hint,
            PendingFollowUpRecommendationMatcher.MatchResult match,
            RoutingEvidence evidence,
            List<PendingFollowUpRecommendation> recommendations,
            List<PendingFollowUpRecommendation> targetCandidates
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
        boolean currentReference = safeEvidence.currentTaskReference();
        boolean continuationSignal = safeEvidence.continuationSignal();

        if (safeHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.EXPLICIT_NEW_TASK) {
            return proceedNewTask(safeHint, PendingFollowUpRecommendationMatcher.MatchResult.none(),
                    currentReference, safeTargetCandidates, "explicit fresh task");
        }
        if (upstreamSuggestsStandaloneTask
                && safeHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED
                && !currentReference
                && !continuationSignal) {
            return proceedNewTask(safeHint, PendingFollowUpRecommendationMatcher.MatchResult.none(),
                    currentReference, safeTargetCandidates, "standalone task has no current-task signal");
        }
        if (safeMatch.type() == PendingFollowUpRecommendationMatcher.Type.ASK_SELECTION
                || safeHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.RELATED_BUT_AMBIGUOUS) {
            return askCurrentTaskSelection(safeHint, safeMatch, currentReference, safeTargetCandidates,
                    "multiple related current-task candidates");
        }
        if (safeMatch.type() == PendingFollowUpRecommendationMatcher.Type.SELECTED && safeMatch.recommendation() != null) {
            if (safeHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.EXACT_OR_PREFIX_MATCH) {
                return proceedCurrentTask(safeHint, safeMatch, currentReference, safeTargetCandidates, "exact carry-forward");
            }
            if (safeHint == PendingFollowUpRecommendationMatcher.CarryForwardHint.SEMANTIC_MATCH_WORTH_LLM) {
                if (upstreamSuggestsStandaloneTask && !currentReference && !continuationSignal) {
                    return proceedNewTask(safeHint, safeMatch, currentReference, safeTargetCandidates,
                            "semantic match lacks current-task signal");
                }
                return proceedCurrentTask(safeHint, safeMatch, currentReference, safeTargetCandidates,
                        "unique semantic current-task continuation");
            }
            if (upstreamSuggestsContinuation) {
                return proceedCurrentTask(safeHint, safeMatch, currentReference, safeTargetCandidates,
                        "upstream continuation selected recommendation");
            }
        }
        if (upstreamSuggestsContinuation
                && safeMatch.type() == PendingFollowUpRecommendationMatcher.Type.NONE
                && (currentReference || continuationSignal)
                && !safeRecommendations.isEmpty()) {
            List<PendingFollowUpRecommendation> candidates = safeTargetCandidates.isEmpty()
                    ? safeRecommendations
                    : safeTargetCandidates;
            return askNewOrCurrent(safeHint, safeMatch, currentReference, candidates,
                    currentReference
                            ? "upstream continuation with unresolved current-task candidates"
                            : "upstream continuation verb with unresolved current-task candidates");
        }
        if (upstreamSuggestsStandaloneTask) {
            if ((currentReference || continuationSignal)
                    && safeTargetCandidates.isEmpty()
                    && !safeRecommendations.isEmpty()) {
                return askNewOrCurrent(safeHint, safeMatch, currentReference, safeRecommendations,
                        currentReference
                                ? "current reference with unresolved current-task candidates"
                                : "continuation verb with unresolved current-task candidates");
            }
            if (currentReference && !safeTargetCandidates.isEmpty()) {
                if (safeTargetCandidates.size() == 1) {
                    return askNewOrCurrent(safeHint, safeMatch, currentReference, safeTargetCandidates,
                            "current reference with weak target match");
                }
                return askCurrentTaskSelection(safeHint, safeMatch, currentReference, safeTargetCandidates,
                        "current reference with multiple target matches");
            }
            if (continuationSignal && safeTargetCandidates.size() == 1) {
                return askNewOrCurrent(safeHint, safeMatch, currentReference, safeTargetCandidates,
                        "continuation verb with weak target match");
            }
            if (continuationSignal && safeTargetCandidates.size() > 1) {
                return askCurrentTaskSelection(safeHint, safeMatch, currentReference, safeTargetCandidates,
                        "continuation verb with multiple target matches");
            }
            return proceedNewTask(safeHint, safeMatch, currentReference, safeTargetCandidates,
                    "standalone task has no current-task signal");
        }
        if (upstreamSuggestsContinuation && safeMatch.type() == PendingFollowUpRecommendationMatcher.Type.SELECTED) {
            return proceedCurrentTask(safeHint, safeMatch, currentReference, safeTargetCandidates,
                    "upstream continuation");
        }
        return noDecision("no current-task arbitration decision");
    }

    private CurrentTaskContinuationArbiter.Decision noDecision(String reason) {
        return new CurrentTaskContinuationArbiter.Decision(
                CurrentTaskContinuationArbiter.DecisionType.NO_DECISION,
                PendingFollowUpRecommendationMatcher.CarryForwardHint.UNRELATED,
                PendingFollowUpRecommendationMatcher.MatchResult.none(),
                null,
                false,
                List.of(),
                reason
        );
    }

    private CurrentTaskContinuationArbiter.Decision proceedNewTask(
            PendingFollowUpRecommendationMatcher.CarryForwardHint hint,
            PendingFollowUpRecommendationMatcher.MatchResult match,
            boolean currentReference,
            List<PendingFollowUpRecommendation> candidates,
            String reason
    ) {
        return new CurrentTaskContinuationArbiter.Decision(
                CurrentTaskContinuationArbiter.DecisionType.PROCEED_NEW_TASK,
                hint,
                match,
                null,
                currentReference,
                candidates == null ? List.of() : candidates,
                reason
        );
    }

    private CurrentTaskContinuationArbiter.Decision proceedCurrentTask(
            PendingFollowUpRecommendationMatcher.CarryForwardHint hint,
            PendingFollowUpRecommendationMatcher.MatchResult match,
            boolean currentReference,
            List<PendingFollowUpRecommendation> candidates,
            String reason
    ) {
        return new CurrentTaskContinuationArbiter.Decision(
                CurrentTaskContinuationArbiter.DecisionType.PROCEED_CURRENT_TASK,
                hint,
                match,
                match == null ? null : match.recommendation(),
                currentReference,
                candidates == null ? List.of() : candidates,
                reason
        );
    }

    private CurrentTaskContinuationArbiter.Decision askNewOrCurrent(
            PendingFollowUpRecommendationMatcher.CarryForwardHint hint,
            PendingFollowUpRecommendationMatcher.MatchResult match,
            boolean currentReference,
            List<PendingFollowUpRecommendation> candidates,
            String reason
    ) {
        List<PendingFollowUpRecommendation> safeCandidates = candidates == null ? List.of() : candidates;
        PendingFollowUpRecommendation selected = safeCandidates.size() == 1 ? safeCandidates.get(0) : null;
        return new CurrentTaskContinuationArbiter.Decision(
                CurrentTaskContinuationArbiter.DecisionType.ASK_NEW_OR_CURRENT,
                hint,
                match,
                selected,
                currentReference,
                safeCandidates,
                reason
        );
    }

    private CurrentTaskContinuationArbiter.Decision askCurrentTaskSelection(
            PendingFollowUpRecommendationMatcher.CarryForwardHint hint,
            PendingFollowUpRecommendationMatcher.MatchResult match,
            boolean currentReference,
            List<PendingFollowUpRecommendation> candidates,
            String reason
    ) {
        return new CurrentTaskContinuationArbiter.Decision(
                CurrentTaskContinuationArbiter.DecisionType.ASK_CURRENT_TASK_SELECTION,
                hint,
                match,
                null,
                currentReference,
                candidates == null ? List.of() : candidates,
                reason
        );
    }
}
