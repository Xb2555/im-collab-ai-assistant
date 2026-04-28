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
@Schema(description = "结果评审输出（评分阶段）")
public class ResultJudgeOutput implements Serializable {

    @Schema(description = "结果评分（0-100）")
    private Integer resultScore;

    @Schema(description = "问题列表")
    private List<String> issues;
}
