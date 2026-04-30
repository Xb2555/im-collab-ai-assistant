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
@Schema(description = "结构化意图快照")
public class IntentSnapshot implements Serializable {

    @Schema(description = "用户目标")
    private String userGoal;

    @Schema(description = "交付物目标列表")
    private List<String> deliverableTargets;

    @Schema(description = "来源范围")
    private WorkspaceContext sourceScope;

    @Schema(description = "时间范围")
    private String timeRange;

    @Schema(description = "目标受众")
    private String audience;

    @Schema(description = "约束条件")
    private List<String> constraints;

    @Schema(description = "缺失槽位")
    private List<String> missingSlots;

    @Schema(description = "场景路径")
    private List<ScenarioCodeEnum> scenarioPath;
}
