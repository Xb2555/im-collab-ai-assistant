package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "场景执行请求")
public class ScenarioExecutionRequest implements Serializable {

    @Schema(description = "场景代码")
    private ScenarioCodeEnum scenarioCode;

    @Schema(description = "浠诲姟ID")
    private String taskId;

    @Schema(description = "执行计划蓝图")
    private PlanBlueprint planBlueprint;
}
