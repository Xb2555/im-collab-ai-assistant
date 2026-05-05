package com.lark.imcollab.common.model.vo;

import com.lark.imcollab.common.model.enums.DocumentArtifactIterationStatus;
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
@Schema(description = "完成态文档产物原地修改结果")
public class DocumentArtifactIterationResult implements Serializable {
    private String taskId;
    private String artifactId;
    private String docUrl;
    private DocumentArtifactIterationStatus status;
    private boolean requireInput;
    private String summary;
    private String preview;
    private List<String> modifiedBlocks;
    private DocumentArtifactApprovalPayload approvalPayload;
}
