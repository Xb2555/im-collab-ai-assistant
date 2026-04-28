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
@Schema(description = "任务事件（状态变更通知）")
public class TaskEvent implements Serializable {

    @Schema(description = "事件ID")
    private String eventId;

    @Schema(description = "任务ID")
    private String taskId;

    @Schema(description = "事件状态（PENDING/ASK_USER/PLAN_READY/EXECUTING/COMPLETED/FAILED/ABORTED）")
    private String status;

    @Schema(description = "规划版本号")
    private int version;

    @Schema(description = "子任务列表")
    private List<AgentTaskPlanCard> subtasks;

    @Schema(description = "需要用户输入的内容")
    private RequireInput requireInput;

    @Schema(description = "事件时间戳")
    @Builder.Default
    private Instant timestamp = Instant.now();
}
