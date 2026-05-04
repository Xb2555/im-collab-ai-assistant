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
    private Integer headingLevel;
    private List<String> bodyBlockIds;
    private List<String> allBlockIds;
    private Integer topLevelIndex;
    private String prevTopLevelSectionId;
    private String nextTopLevelSectionId;
}
