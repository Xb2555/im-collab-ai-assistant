package com.lark.imcollab.common.model.dto;

import com.lark.imcollab.common.model.entity.WorkspaceContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "任务恢复请求")
public class ResumeRequest {

    @Schema(description = "用户反馈/澄清回答")
    private String feedback;

    @Schema(description = "追问恢复阶段补充的工作区上下文（选中消息、文档引用等）")
    private WorkspaceContext workspaceContext;

    @Schema(description = "是否从头重新规划")
    private boolean replanFromRoot;
}
