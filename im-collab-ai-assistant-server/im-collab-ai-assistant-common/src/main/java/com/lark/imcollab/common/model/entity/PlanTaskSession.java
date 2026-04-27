package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
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
@Schema(description = "任务规划会话")
public class PlanTaskSession implements Serializable {

    @Schema(description = "任务ID")
    private String taskId;

    @Schema(description = "规划阶段（ASK_USER/PLAN_READY/EXECUTING/COMPLETED/FAILED/ABORTED）")
    private PlanningPhaseEnum planningPhase;

    @Schema(description = "规划版本号")
    @Builder.Default
    private int version = 0;

    @Schema(description = "规划评分")
    @Builder.Default
    private int planScore = 0;

    @Schema(description = "是否已中止")
    @Builder.Default
    private boolean aborted = false;

    @Schema(description = "对话轮次")
    @Builder.Default
    private int turnCount = 0;

    @Schema(description = "状态变更原因")
    private String transitionReason;

    @Schema(description = "澄清问题列表")
    private List<String> clarificationQuestions;

    @Schema(description = "澄清回答列表")
    private List<String> clarificationAnswers;

    @Schema(description = "任务卡片列表")
    private List<UserPlanCard> planCards;
}
