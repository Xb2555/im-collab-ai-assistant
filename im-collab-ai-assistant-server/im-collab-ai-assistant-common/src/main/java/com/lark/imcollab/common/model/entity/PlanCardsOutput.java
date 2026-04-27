package com.lark.imcollab.common.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "规划输出（用于 outputType 结构化约束）")
public class PlanCardsOutput implements Serializable {

    @Schema(description = "任务卡片列表")
    private List<UserPlanCard> planCards;
}
