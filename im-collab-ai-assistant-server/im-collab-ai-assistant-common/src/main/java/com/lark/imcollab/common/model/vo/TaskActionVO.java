package com.lark.imcollab.common.model.vo;

public record TaskActionVO(
        boolean canConfirm,
        boolean canReplan,
        boolean canCancel,
        boolean canResume,
        boolean canInterrupt,
        boolean canRetry
) {
}
