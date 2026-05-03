package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
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
public class DocumentEditPlan implements Serializable {
    private String taskId;
    private DocumentIterationIntentType intentType;
    private DocumentTargetSelector selector;
    private String reasoningSummary;
    private String generatedContent;
    private String styleProfile;
    private String mediaSpec;
    private String layoutSpec;
    private DocumentPatchOperationType toolCommandType;
    private boolean requiresApproval;
    private DocumentRiskLevel riskLevel;
    private List<DocumentPatchOperation> patchOperations;
}
