package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
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

    @Schema(description = "输入上下文")
    private TaskInputContext inputContext;

    @Schema(description = "入口判定状态")
    private TaskIntakeState intakeState;

    @Schema(description = "澄清问题列表")
    private List<String> clarificationQuestions;

    @Schema(description = "澄清回答列表")
    private List<String> clarificationAnswers;

    @Schema(description = "当前澄清槽位")
    private List<PromptSlotState> activePromptSlots;

    @Schema(description = "结构化意图快照")
    private IntentSnapshot intentSnapshot;

    @Schema(description = "计划蓝图")
    private PlanBlueprint planBlueprint;

    @Schema(description = "计划蓝图摘要")
    private String planBlueprintSummary;

    @Schema(description = "任务卡片列表")
    private List<UserPlanCard> planCards;

    @Schema(description = "场景路径")
    private List<ScenarioCodeEnum> scenarioPath;

    @Schema(description = "后续场景接入挂钩")
    private List<ScenarioIntegrationHook> integrationHooks;

    @Schema(description = "用户职业角色")
    private String profession;

    @Schema(description = "行业领域")
    private String industry;

    @Schema(description = "目标受众")
    private String audience;

    @Schema(description = "偏好风格")
    private String tone;

    @Schema(description = "输出语言")
    private String language;

    @Schema(description = "Prompt 模板配置档（default/pm/sales）")
    private String promptProfile;

    @Schema(description = "Prompt 模板版本（v1/v2/v3）")
    private String promptVersion;
}
