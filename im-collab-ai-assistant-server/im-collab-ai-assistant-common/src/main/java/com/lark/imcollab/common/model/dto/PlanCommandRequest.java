package com.lark.imcollab.common.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "任务指令请求")
public class PlanCommandRequest {

    @Schema(description = "执行动作（CONFIRM_EXECUTE/REPLAN/CANCEL）", example = "CONFIRM_EXECUTE")
    private String action;

    @Schema(description = "用户反馈（如需重规划）")
    private String feedback;

    @Schema(description = "任务版本号（用于冲突检测）", example = "1")
    private int version;
}
