package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.AgentTaskTypeEnum;
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
@Schema(description = "Agent任务卡片（子任务执行单元）")
public class AgentTaskPlanCard implements Serializable {

    @Schema(description = "任务ID")
    private String taskId;

    @Schema(description = "父卡片ID")
    private String parentCardId;

    @Schema(description = "任务类型（INTENT_PARSING/FETCH_CONTEXT/SEARCH_WEB/WRITE_DOC/WRITE_SLIDES/GENERATE_SUMMARY/WRITE_FLYSHEET）")
    private AgentTaskTypeEnum taskType;

    @Schema(description = "状态（PENDING/RUNNING/COMPLETED/FAILED）")
    @Builder.Default
    private String status = "PENDING";

    @Schema(description = "输入内容")
    private String input;

    @Schema(description = "输出内容")
    private String output;

    @Schema(description = "可用工具列表")
    private List<String> tools;

    @Schema(description = "执行上下文")
    private String context;

    @Schema(description = "上次结果评分（0-100）")
    @Builder.Default
    private int lastResultScore = 0;

    @Schema(description = "上次判决结果（PASS/RETRY/HUMAN_REVIEW）")
    private String lastVerdict;

    @Schema(description = "重试次数")
    @Builder.Default
    private int retryCount = 0;
}
