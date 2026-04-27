package com.lark.imcollab.common.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "需要用户输入的内容")
public class RequireInput implements Serializable {

    @Schema(description = "输入类型（CLARIFICATION/CONFIRMATION/CHOICE）")
    private String type;

    @Schema(description = "提示文本")
    private String prompt;

    @Schema(description = "可选选项（如为选择题）")
    private List<String> options;
}
