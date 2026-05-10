package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PresentationEditability;
import com.lark.imcollab.common.model.enums.PresentationElementKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresentationAssetRef implements Serializable {
    private String assetId;
    private String fileToken;
    private String sourceRef;
    private String sourceType;
    private PresentationElementKind elementKind;
    private PresentationEditability editability;
    private String altText;
    private String caption;
}
