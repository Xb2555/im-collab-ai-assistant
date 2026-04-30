package com.lark.imcollab.planner.replan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanPatchIntent {

    private PlanPatchOperation operation;
    private List<String> targetCardIds;
    private List<String> orderedCardIds;
    private List<PlanPatchCardDraft> newCardDrafts;
    private double confidence;
    private String reason;
    private String clarificationQuestion;

    public boolean confident() {
        return confidence >= 0.65d;
    }
}
