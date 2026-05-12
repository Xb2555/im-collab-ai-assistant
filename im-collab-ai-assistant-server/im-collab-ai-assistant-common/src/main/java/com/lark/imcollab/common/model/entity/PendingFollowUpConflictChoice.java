package com.lark.imcollab.common.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "新任务与 follow-up 冲突时，等待用户 1/2 选择的挂起态")
public class PendingFollowUpConflictChoice implements Serializable {

    @Schema(description = "会话键")
    private String conversationKey;

    @Schema(description = "用户原始输入")
    private String originalInstruction;

    @Schema(description = "命中的推荐 ID")
    private String selectedRecommendationId;

    @Schema(description = "命中的推荐标题")
    private String selectedRecommendationTitle;

    @Schema(description = "命中的推荐规划指令")
    private String selectedRecommendationPlannerInstruction;

    @Schema(description = "命中的推荐目标任务 ID")
    private String targetTaskId;

    @Schema(description = "按新任务处理时沿用的指令")
    private String newTaskInstruction;

    @Schema(description = "过期时间")
    private Instant expiresAt;
}
