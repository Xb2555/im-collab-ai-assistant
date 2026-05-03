package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.DocumentAnchorType;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import com.lark.imcollab.common.model.enums.DocumentStrategyType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEditStrategy implements Serializable {
    private DocumentStrategyType strategyType;
    private DocumentAnchorType anchorType;
    private DocumentPatchOperationType patchFamily;
    private String generatedContent;
    private ExpectedDocumentState expectedState;
    private boolean requiresApproval;
    private DocumentRiskLevel riskLevel;
}
