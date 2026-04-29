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
@Schema(description = "执行契约")
public class ExecutionContract implements Serializable {

    @Schema(description = "任务 ID")
    private String taskId;

    @Schema(description = "用户原始输入，不可覆盖")
    private String rawInstruction;

    @Schema(description = "融合澄清后的完整目标")
    private String clarifiedInstruction;

    @Schema(description = "执行摘要，用于展示")
    private String taskBrief;

    @Schema(description = "用户明确请求的交付物")
    private List<String> requestedArtifacts;

    @Schema(description = "当前允许生成的交付物")
    private List<String> allowedArtifacts;

    @Schema(description = "主交付物")
    private String primaryArtifact;

    @Schema(description = "跨交付物策略（如 FORBID_UNLESS_EXPLICIT）")
    private String crossArtifactPolicy;

    @Schema(description = "受众")
    private String audience;

    @Schema(description = "时间范围")
    private String timeScope;

    @Schema(description = "约束条件")
    private List<String> constraints;

    @Schema(description = "来源范围")
    private WorkspaceContext sourceScope;

    @Schema(description = "上下文引用")
    private List<String> contextRefs;

    @Schema(description = "模板策略")
    private String templateStrategy;

    @Schema(description = "图表需求")
    private DiagramRequirement diagramRequirement;

    @Schema(description = "冻结时间")
    private Instant frozenAt;
}
