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
@Schema(description = "鍦烘櫙鎵ц缁撴灉")
public class ScenarioExecutionResult implements Serializable {

    @Schema(description = "鏄惁鎴愬姛")
    private boolean success;

    @Schema(description = "缁撴灉鎻忚堪")
    private String message;
}
