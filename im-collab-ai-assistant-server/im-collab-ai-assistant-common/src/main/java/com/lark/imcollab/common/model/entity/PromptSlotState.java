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
@Schema(description = "婢勬竻妲戒綅鐘舵€?")
public class PromptSlotState implements Serializable {

    @Schema(description = "妲戒綅閿?")
    private String slotKey;

    @Schema(description = "闂鎻愮ず")
    private String prompt;

    @Schema(description = "妲戒綅鍊?")
    private String value;

    @Builder.Default
    @Schema(description = "鏄惁宸插洖绛?")
    private boolean answered = false;
}
