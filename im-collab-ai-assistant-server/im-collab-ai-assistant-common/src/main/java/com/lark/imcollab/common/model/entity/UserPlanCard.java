package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
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
public class UserPlanCard implements Serializable {

    private String cardId;

    private String taskId;

    @Builder.Default
    private int version = 0;

    private String title;

    private String description;

    private PlanCardTypeEnum type;

    @Builder.Default
    private String status = "PENDING";

    @Builder.Default
    private int progress = 0;

    private List<String> dependsOn;

    private List<String> availableActions;

    private List<AgentTaskPlanCard> agentTaskPlanCards;
}
