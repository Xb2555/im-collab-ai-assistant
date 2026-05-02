package com.lark.imcollab.common.model.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "浠诲姟鍏ュ彛绫诲瀷")
public enum TaskIntakeTypeEnum {
    NEW_TASK,
    CLARIFICATION_REPLY,
    PLAN_ADJUSTMENT,
    STATUS_QUERY,
    CONFIRM_ACTION,
    CANCEL_TASK,
    UNKNOWN
}
