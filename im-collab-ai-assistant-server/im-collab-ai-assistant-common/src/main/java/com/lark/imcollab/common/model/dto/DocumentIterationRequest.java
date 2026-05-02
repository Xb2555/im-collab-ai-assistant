package com.lark.imcollab.common.model.dto;

import com.lark.imcollab.common.model.entity.WorkspaceContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文档迭代请求")
public class DocumentIterationRequest implements Serializable {

    @Schema(description = "可选：已有任务 ID")
    private String taskId;

    @Schema(description = "飞书文档 URL 或 token", requiredMode = Schema.RequiredMode.REQUIRED)
    private String docUrl;

    @Schema(description = "自然语言编辑指令", requiredMode = Schema.RequiredMode.REQUIRED)
    private String instruction;

    @Schema(description = "工作空间上下文")
    private WorkspaceContext workspaceContext;
}
