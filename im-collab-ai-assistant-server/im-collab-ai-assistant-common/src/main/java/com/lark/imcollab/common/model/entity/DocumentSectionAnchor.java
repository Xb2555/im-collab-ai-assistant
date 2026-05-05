package com.lark.imcollab.common.model.entity;

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
public class DocumentSectionAnchor implements Serializable {
    private String headingBlockId;
    private String headingText;
    private String headingNumber;
    private Integer headingLevel;
    private List<String> bodyBlockIds;
    private List<String> allBlockIds;
    private Integer topLevelIndex;
    private String parentHeadingBlockId;
    private List<String> pathHeadingIds;
    private List<String> pathNumbers;
    private Integer siblingOrdinalWithinParent;
    private Integer absoluteOrder;
    private String prevTopLevelSectionId;
    private String nextTopLevelSectionId;
}
