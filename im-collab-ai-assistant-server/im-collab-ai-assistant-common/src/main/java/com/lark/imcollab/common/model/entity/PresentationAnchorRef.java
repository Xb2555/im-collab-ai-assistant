package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PresentationAnchorMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresentationAnchorRef implements Serializable {
    private PresentationAnchorMode anchorMode;
    private Integer pageIndex;
    private String slideId;
    private String quotedText;
    private String elementRole;
    private String blockId;
    private Integer expectedMatchCount;
}
