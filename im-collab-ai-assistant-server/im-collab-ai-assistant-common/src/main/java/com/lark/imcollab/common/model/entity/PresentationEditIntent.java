package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationIterationIntentType;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
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
public class PresentationEditIntent implements Serializable {

    private PresentationIterationIntentType intentType;
    private PresentationEditActionType actionType;
    private String userInstruction;
    private Integer pageIndex;
    private String replacementText;
    private PresentationTargetElementType targetElementType;
    private List<PresentationEditOperation> operations;
    private boolean clarificationNeeded;
    private String clarificationHint;
}
