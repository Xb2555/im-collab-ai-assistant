package com.lark.imcollab.common.model.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "规划阶段枚举")
public enum PlanningPhaseEnum {
    INTAKE,
    ASK_USER,
    INTENT_READY,
    PLAN_READY,
    EXECUTING,
    INTERRUPTING,
    REPLANNING,
    COMPLETED,
    FAILED,
    ABORTED
}
