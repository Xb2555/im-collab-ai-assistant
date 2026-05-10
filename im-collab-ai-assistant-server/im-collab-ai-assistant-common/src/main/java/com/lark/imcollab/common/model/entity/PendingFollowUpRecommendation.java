package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
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
@Schema(description = "会话内待承接的下一步推荐")
public class PendingFollowUpRecommendation implements Serializable {

    @Schema(description = "推荐 ID")
    private String recommendationId;

    @Schema(description = "命中的目标任务 ID")
    private String targetTaskId;

    @Schema(description = "后续动作模式")
    private FollowUpModeEnum followUpMode;

    @Schema(description = "来源产物 ID")
    private String sourceArtifactId;

    @Schema(description = "来源产物类型")
    private ArtifactTypeEnum sourceArtifactType;

    @Schema(description = "目标交付物类型")
    private ArtifactTypeEnum targetDeliverable;

    @Schema(description = "后续规划内部指令")
    private String plannerInstruction;

    @Schema(description = "产物策略")
    private String artifactPolicy;

    @Schema(description = "用户可直接回复的建议指令")
    private String suggestedUserInstruction;

    @Schema(description = "优先级")
    private int priority;
}
