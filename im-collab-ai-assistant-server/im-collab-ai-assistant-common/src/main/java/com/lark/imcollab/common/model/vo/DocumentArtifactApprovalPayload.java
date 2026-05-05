package com.lark.imcollab.common.model.vo;

import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
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
@Schema(description = "完成态文档产物修改待确认载荷")
public class DocumentArtifactApprovalPayload implements Serializable {
    private DocumentRiskLevel riskLevel;
    private String targetPreview;
    private String generatedContent;
}
