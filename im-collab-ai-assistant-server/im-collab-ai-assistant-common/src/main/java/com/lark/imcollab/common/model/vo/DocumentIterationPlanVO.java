package com.lark.imcollab.common.model.vo;

import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentAnchorType;
import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
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
public class DocumentIterationPlanVO implements Serializable {
    private DocumentIterationIntentType intentType;
    private DocumentSemanticActionType semanticAction;
    private DocumentAnchorType anchorType;
    private DocumentStrategyType strategyType;
    private DocumentExpectedStateType expectedState;
    private String targetTitle;
    private String targetPreview;
    private String generatedContent;
    private DocumentPatchOperationType toolCommandType;
    private boolean requiresApproval;
    private DocumentRiskLevel riskLevel;
}
