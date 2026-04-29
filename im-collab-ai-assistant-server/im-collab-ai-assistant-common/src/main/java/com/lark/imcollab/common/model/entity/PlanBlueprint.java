package com.lark.imcollab.common.model.entity;

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
@Schema(description = "完整计划蓝图")
public class PlanBlueprint implements Serializable {

    @Schema(description = "任务摘要")
    private String taskBrief;

    @Schema(description = "场景路径")
    private List<ScenarioCodeEnum> scenarioPath;

    @Schema(description = "交付物列表")
    private List<String> deliverables;

    @Schema(description = "来源范围")
    private WorkspaceContext sourceScope;

    @Schema(description = "约束条件")
    private List<String> constraints;

    @Schema(description = "成功标准")
    private List<String> successCriteria;

    @Schema(description = "风险提示")
    private List<String> risks;

    @Schema(description = "计划卡片")
    private List<UserPlanCard> planCards;
}
