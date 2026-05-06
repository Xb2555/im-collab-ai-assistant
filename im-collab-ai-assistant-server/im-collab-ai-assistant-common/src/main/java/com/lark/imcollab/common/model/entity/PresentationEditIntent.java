package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationIterationIntentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

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
    private boolean clarificationNeeded;
    private String clarificationHint;
}
