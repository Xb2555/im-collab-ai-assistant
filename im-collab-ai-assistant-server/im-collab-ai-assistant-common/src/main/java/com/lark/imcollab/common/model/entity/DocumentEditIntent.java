package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;
import com.lark.imcollab.common.model.entity.MediaAssetSpec;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEditIntent implements Serializable {
    private DocumentIterationIntentType intentType;
    private DocumentSemanticActionType semanticAction;
    private String userInstruction;
    private Map<String, String> parameters;
    private MediaAssetSpec assetSpec;
}
