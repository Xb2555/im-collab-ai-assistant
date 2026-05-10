package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.FollowUpModeEnum;
import com.lark.imcollab.common.model.enums.NextStepRecommendationCodeEnum;
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
@Schema(description = "任务完成后的下一步推荐")
public class NextStepRecommendation implements Serializable {

    @Schema(description = "推荐动作编码")
    private NextStepRecommendationCodeEnum code;

    @Schema(description = "推荐 ID")
    private String recommendationId;

    @Schema(description = "推荐标题")
    private String title;

    @Schema(description = "推荐理由")
    private String reason;

    @Schema(description = "建议用户直接发送的下一句指令")
    private String suggestedUserInstruction;

    @Schema(description = "目标产物类型")
    private ArtifactTypeEnum targetDeliverable;

    @Schema(description = "后续动作模式")
    private FollowUpModeEnum followUpMode;

    @Schema(description = "命中的目标任务 ID")
    private String targetTaskId;

    @Schema(description = "来源产物 ID")
    private String sourceArtifactId;

    @Schema(description = "来源产物类型")
    private ArtifactTypeEnum sourceArtifactType;

    @Schema(description = "推荐命中后的内部规划指令")
    private String plannerInstruction;

    @Schema(description = "产物策略")
    private String artifactPolicy;

    @Schema(description = "优先级，数字越小优先级越高")
    private int priority;
}
