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
@Schema(description = "下一步推荐 Agent 输出")
public class NextStepRecommendationOutput implements Serializable {

    @Schema(description = "推荐列表")
    private List<NextStepRecommendation> recommendations;
}
