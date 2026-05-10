package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationAnchorMode;
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
    private String targetSlideId;
    private Integer insertAfterPageIndex;
    private String slideTitle;
    private String slideBody;
    private String replacementText;
    private PresentationTargetElementType targetElementType;
    private PresentationAnchorMode anchorMode;
    private String quotedText;
    private String elementRole;
    private Integer expectedMatchCount;
    private String contentInstruction;
    private String targetElementId;
    private String targetBlockId;
    private List<PresentationEditOperation> operations;
    private boolean clarificationNeeded;
    private String clarificationHint;
}
