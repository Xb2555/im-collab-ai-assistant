package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PresentationEditability;
import com.lark.imcollab.common.model.enums.PresentationElementKind;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresentationElementIR implements Serializable {
    private String elementId;
    private PresentationElementKind elementKind;
    private PresentationTargetElementType targetElementType;
    private String semanticRole;
    private String textType;
    private String textContent;
    private PresentationLayoutSpec layoutBox;
    private PresentationAssetRef assetRef;
    private PresentationEditability editability;
    private String originBlockId;
}
