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
@Schema(description = "鍦烘櫙鎵ц璇锋眰")
public class ScenarioExecutionRequest implements Serializable {

    @Schema(description = "鍦烘櫙浠ｇ爜")
    private ScenarioCodeEnum scenarioCode;

    @Schema(description = "浠诲姟ID")
    private String taskId;

    @Schema(description = "鎵ц鏂囦笂鏂?")
    private PlanBlueprint planBlueprint;
}
