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
@Schema(description = "完成态文档产物原地修改请求")
public class DocumentArtifactIterationRequest implements Serializable {

    @Schema(description = "原完成态任务 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskId;

    @Schema(description = "目标文档产物 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String artifactId;

    @Schema(description = "飞书文档 URL", requiredMode = Schema.RequiredMode.REQUIRED)
    private String docUrl;

    @Schema(description = "自然语言修改指令", requiredMode = Schema.RequiredMode.REQUIRED)
    private String instruction;

    @Schema(description = "操作者 openId")
    private String operatorOpenId;

    @Schema(description = "工作空间上下文")
    private WorkspaceContext workspaceContext;
}
