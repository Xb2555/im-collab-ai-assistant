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
@Schema(description = "结果裁决输出（建议阶段）")
public class ResultAdviceOutput implements Serializable {

    @Schema(description = "判决结果（PASS/RETRY/HUMAN_REVIEW）")
    private String verdict;

    @Schema(description = "建议列表")
    private List<String> suggestions;
}
