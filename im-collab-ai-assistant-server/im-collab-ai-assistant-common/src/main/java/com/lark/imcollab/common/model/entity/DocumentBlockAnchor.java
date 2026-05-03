package com.lark.imcollab.common.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentBlockAnchor implements Serializable {
    private String blockId;
    private String blockType;
    private String plainText;
    private String topLevelAncestorId;
    private String nextBlockId;
    private String prevBlockId;
}
