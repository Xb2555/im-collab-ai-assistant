package com.lark.imcollab.common.model.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "任务结果判决枚举")
public enum ResultVerdictEnum {
    PASS,
    RETRY,
    HUMAN_REVIEW
}
