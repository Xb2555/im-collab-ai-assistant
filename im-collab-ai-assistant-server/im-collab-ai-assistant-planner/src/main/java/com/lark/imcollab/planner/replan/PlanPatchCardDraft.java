package com.lark.imcollab.planner.replan;

import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanPatchCardDraft {

    private String title;
    private String description;
    private PlanCardTypeEnum type;
}
