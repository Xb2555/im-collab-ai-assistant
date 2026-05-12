package com.lark.imcollab.planner.service;

import org.springframework.stereotype.Service;

/**
 * Backward-compatible bean name for the broader current-task continuation arbiter.
 */
@Service
public class PendingFollowUpConflictArbiter extends CurrentTaskContinuationArbiter {

    public PendingFollowUpConflictArbiter(PendingFollowUpRecommendationMatcher matcher) {
        super(matcher);
    }
}
