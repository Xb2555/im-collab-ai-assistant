package com.lark.imcollab.common.model.entity;

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
@Schema(description = "场景执行结果")
public class ScenarioExecutionResult implements Serializable {

    @Schema(description = "是否成功")
    private boolean success;

    @Schema(description = "结果描述")
    private String message;
}
