package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationAnchorMode;
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
public class PresentationEditOperation implements Serializable {

    private PresentationEditActionType actionType;
    private PresentationTargetElementType targetElementType;
    private Integer pageIndex;
    private String targetSlideId;
    private Integer insertAfterPageIndex;
    private String slideTitle;
    private String slideBody;
    private String replacementText;
    private PresentationAnchorMode anchorMode;
    private String quotedText;
    private String elementRole;
    private Integer expectedMatchCount;
    private String contentInstruction;
    private String targetElementId;
    private String targetBlockId;
}
