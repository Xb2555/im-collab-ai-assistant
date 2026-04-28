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
@Schema(description = "鍦烘櫙妯″潡鎺ュ叆鎸傞挬")
public class ScenarioIntegrationHook implements Serializable {

    @Schema(description = "鍦烘櫙浠ｇ爜")
    private ScenarioCodeEnum scenarioCode;

    @Schema(description = "妯″潡鍚嶇О")
    private String moduleName;

    @Schema(description = "鍚庣画澶勭悊鐘舵€?")
    private String status;
}
