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
@Schema(description = "澄清槽位状态")
public class PromptSlotState implements Serializable {

    @Schema(description = "槽位键")
    private String slotKey;

    @Schema(description = "问题提示")
    private String prompt;

    @Schema(description = "槽位值")
    private String value;

    @Builder.Default
    @Schema(description = "是否已回答")
    private boolean answered = false;
}
