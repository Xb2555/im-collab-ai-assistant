package com.lark.imcollab.common.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "任务恢复请求")
public class ResumeRequest {

    @Schema(description = "用户反馈/澄清回答")
    private String feedback;

    @Schema(description = "是否从头重新规划")
    private boolean replanFromRoot;
}
