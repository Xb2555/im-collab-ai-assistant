package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务结果评估")
public class TaskResultEvaluation implements Serializable {

    @Schema(description = "任务ID")
    private String taskId;

    @Schema(description = "Agent任务ID")
    private String agentTaskId;

    @Schema(description = "结果评分（0-100）")
    @Builder.Default
    private int resultScore = 0;

    @Schema(description = "判决结果（PASS/RETRY/HUMAN_REVIEW）")
    private ResultVerdictEnum verdict;

    @Schema(description = "发现的问题列表")
    private List<String> issues;

    @Schema(description = "建议列表")
    private List<String> suggestions;

    @Schema(description = "评估时间")
    @Builder.Default
    private Instant evaluatedAt = Instant.now();
}
