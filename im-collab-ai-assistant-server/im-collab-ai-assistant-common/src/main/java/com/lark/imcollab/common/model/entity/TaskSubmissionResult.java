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
@Schema(description = "任务提交结果")
public class TaskSubmissionResult implements Serializable {

    @Schema(description = "任务ID")
    private String taskId;

    @Schema(description = "父卡片ID")
    private String parentCardId;

    @Schema(description = "Agent任务ID")
    private String agentTaskId;

    @Schema(description = "状态（COMPLETED/FAILED）")
    @Builder.Default
    private String status = "COMPLETED";

    @Schema(description = "产出物引用列表")
    private List<String> artifactRefs;

    @Schema(description = "原始输出内容")
    private String rawOutput;

    @Schema(description = "错误信息")
    private String errorMessage;

    @Schema(description = "提交时间")
    @Builder.Default
    private Instant submittedAt = Instant.now();
}
