package com.lark.imcollab.planner.service;

import com.lark.imcollab.planner.config.PlannerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Backward-compatible bean name for the broader current-task continuation arbiter.
 */
@Service
public class PendingFollowUpConflictArbiter extends CurrentTaskContinuationArbiter {

    PendingFollowUpConflictArbiter(PendingFollowUpRecommendationMatcher matcher) {
        super(matcher);
    }

    @Autowired
    public PendingFollowUpConflictArbiter(PendingFollowUpRecommendationMatcher matcher, PlannerProperties properties) {
        super(matcher, properties);
    }
}
