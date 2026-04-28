package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
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
@Schema(description = "浠诲姟鍏ュ彛鐘舵€?")
public class TaskIntakeState implements Serializable {

    @Schema(description = "鍏ュ彛鍒ゆ柇绫诲瀷")
    private TaskIntakeTypeEnum intakeType;

    @Schema(description = "鏄惁缁帴鏃ф浼氳瘽")
    private boolean continuedConversation;

    @Schema(description = "浼氳瘽缁戝畾閿?")
    private String continuationKey;

    @Schema(description = "鏈€杩戜竴娆＄敤鎴疯緭鍏?")
    private String lastUserMessage;

    @Schema(description = "鏈€杩戜竴娆¤緭鍏ユ椂闂?")
    private String lastInputAt;
}
