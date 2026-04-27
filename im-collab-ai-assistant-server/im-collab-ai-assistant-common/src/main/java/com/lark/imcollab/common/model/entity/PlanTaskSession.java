package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanTaskSession implements Serializable {

    private String taskId;

    private PlanningPhaseEnum planningPhase;

    @Builder.Default
    private int version = 0;

    @Builder.Default
    private int planScore = 0;

    @Builder.Default
    private boolean aborted = false;

    @Builder.Default
    private int turnCount = 0;

    private String transitionReason;

    private List<String> clarificationQuestions;

    private List<String> clarificationAnswers;

    private List<UserPlanCard> planCards;
}
