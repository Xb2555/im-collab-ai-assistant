package com.lark.imcollab.common.model.vo;

import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIterationPlanVO implements Serializable {
    private DocumentIterationIntentType intentType;
    private String targetTitle;
    private String targetPreview;
    private String generatedContent;
    private DocumentPatchOperationType toolCommandType;
    private boolean requiresApproval;
    private DocumentRiskLevel riskLevel;
}
