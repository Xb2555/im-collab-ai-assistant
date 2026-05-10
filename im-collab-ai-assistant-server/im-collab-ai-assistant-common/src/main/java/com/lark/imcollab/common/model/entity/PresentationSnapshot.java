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
public class PresentationSnapshot implements Serializable {
    private String slideId;
    private Integer pageIndex;
    private String elementId;
    private String blockId;
    private String nodePath;
    private PresentationElementKind elementKind;
    private String textType;
    private String textContent;
    private String normalizedText;
    private Integer paragraphIndex;
    private Integer listItemIndex;
    private PresentationLayoutSpec boundingBox;
    private String semanticRole;
    private PresentationEditability editability;
}
