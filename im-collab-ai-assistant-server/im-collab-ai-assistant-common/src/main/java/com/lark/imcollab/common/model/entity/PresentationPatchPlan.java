package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PresentationPatchActionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresentationPatchPlan implements Serializable {
    private String slideId;
    private String targetElementId;
    private String targetBlockId;
    private PresentationPatchActionType actionType;
    private String replacementXml;
    private String verificationText;
    private String summary;
}
