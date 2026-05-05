package com.lark.imcollab.common.model.dto;

import com.lark.imcollab.common.model.entity.WorkspaceContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "任务指令请求")
public class PlanCommandRequest {

    @Schema(description = "执行动作（CONFIRM_EXECUTE/REPLAN/RESUME/CANCEL/RETRY_FAILED）", example = "CONFIRM_EXECUTE")
    private String action;

    @Schema(description = "用户反馈（如需重规划或回答 Agent 追问）")
    private String feedback;

    @Schema(description = "产物处理策略（AUTO/EDIT_EXISTING/CREATE_NEW/KEEP_EXISTING_CREATE_NEW）", example = "AUTO")
    private String artifactPolicy;

    @Schema(description = "可选：指定要调整的已有产物 ID")
    private String targetArtifactId;

    @Schema(description = "追问/重规划阶段补充的工作区上下文（选中消息、文档引用等）")
    private WorkspaceContext workspaceContext;

    @Schema(description = "任务版本号（用于冲突检测）", example = "1")
    private int version;
}
