package com.lark.imcollab.common.model.dto;

import com.lark.imcollab.common.model.entity.WorkspaceContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "执行下一步推荐请求")
public class RecommendationExecuteRequest {

    @Schema(description = "任务版本号（用于冲突检测）", example = "1")
    private int version;

    @Schema(description = "可选：补充的工作区上下文（选中消息、文档引用等）")
    private WorkspaceContext workspaceContext;
}
