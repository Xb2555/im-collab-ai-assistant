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
@Schema(description = "场景模块接入挂钩")
public class ScenarioIntegrationHook implements Serializable {

    @Schema(description = "场景代码")
    private ScenarioCodeEnum scenarioCode;

    @Schema(description = "模块名称")
    private String moduleName;

    @Schema(description = "后续处理状态")
    private String status;
}
