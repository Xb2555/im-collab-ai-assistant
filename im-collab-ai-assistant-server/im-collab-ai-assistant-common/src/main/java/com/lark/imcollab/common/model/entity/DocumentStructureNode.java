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
public class DocumentStructureNode implements Serializable {
    private String blockId;
    private String blockType;
    private Integer headingLevel;
    private String titleText;
    private String plainText;
    private String parentBlockId;
    private List<String> children;
    private String topLevelAncestorId;
    private String prevSiblingId;
    private String nextSiblingId;
    private Integer topLevelIndex;
}
