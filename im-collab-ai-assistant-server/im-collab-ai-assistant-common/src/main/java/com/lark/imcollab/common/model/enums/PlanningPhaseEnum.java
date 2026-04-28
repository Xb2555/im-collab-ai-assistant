package com.lark.imcollab.common.model.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "规划阶段枚举")
public enum PlanningPhaseEnum {
    ASK_USER,
    PLAN_READY,
    EXECUTING,
    COMPLETED,
    FAILED,
    ABORTED
}
