package com.lark.imcollab.common.model.entity;

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
@Schema(description = "新开任务与继续当前任务之间等待用户 1/2 选择的挂起态")
public class PendingCurrentTaskContinuationChoice implements Serializable {

    @Schema(description = "会话键")
    private String conversationKey;

    @Schema(description = "用户原始输入")
    private String originalInstruction;

    @Schema(description = "继续当前任务的目标任务 ID")
    private String targetTaskId;

    @Schema(description = "继续当前任务的候选类型，例如 FOLLOW_UP_RECOMMENDATION / CURRENT_TASK_ADJUSTMENT")
    private String continuationType;

    @Schema(description = "唯一命中的推荐 ID")
    private String selectedRecommendationId;

    @Schema(description = "可能相关的推荐 ID 列表")
    private List<String> candidateRecommendationIds;

    @Schema(description = "按新任务处理时沿用的指令")
    private String newTaskInstruction;

    @Schema(description = "过期时间")
    private Instant expiresAt;
}
