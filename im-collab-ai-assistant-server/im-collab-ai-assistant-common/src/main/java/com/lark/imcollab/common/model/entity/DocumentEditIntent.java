package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
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
public class DocumentEditIntent implements Serializable {
    private DocumentIterationIntentType intentType;
    private DocumentSemanticActionType semanticAction;
    private String userInstruction;
    private DocumentAnchorSpec anchorSpec;
    private DocumentRewriteSpec rewriteSpec;
    private MediaAssetSpec assetSpec;
    private boolean clarificationNeeded;
    private String clarificationHint;
    private DocumentRiskLevel riskLevel;
    private List<String> riskHints;
}
