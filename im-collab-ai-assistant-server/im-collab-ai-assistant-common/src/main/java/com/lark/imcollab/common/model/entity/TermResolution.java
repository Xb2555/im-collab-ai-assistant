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
@Schema(description = "术语消歧结果")
public class TermResolution implements Serializable {

    @Schema(description = "原始术语")
    private String term;

    @Schema(description = "解析后的语义标识")
    private String resolvedMeaning;

    @Schema(description = "置信度（HIGH/MEDIUM/LOW）")
    private String confidence;

    @Schema(description = "解析原因")
    private String rationale;

    @Schema(description = "候选语义列表")
    private List<String> candidateMeanings;
}
