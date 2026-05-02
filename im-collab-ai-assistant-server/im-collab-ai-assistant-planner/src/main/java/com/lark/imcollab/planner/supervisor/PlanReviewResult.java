package com.lark.imcollab.planner.supervisor;

import java.util.List;

public record PlanReviewResult(
        boolean passed,
        boolean needsRevision,
        List<String> issues,
        String message
) {

    public static PlanReviewResult passed(String message) {
        return new PlanReviewResult(true, false, List.of(), message == null ? "" : message);
    }

    public static PlanReviewResult rejected(List<String> issues, String message) {
        return new PlanReviewResult(false, true, issues == null ? List.of() : List.copyOf(issues), message == null ? "" : message);
    }
}
