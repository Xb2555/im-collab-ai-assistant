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
public class DocumentTextAnchor implements Serializable {
    private String matchedText;
    private int matchCount;
    private String surroundingContext;
    private String sourceBlockId;
    private List<String> sourceBlockIds;
    private Integer matchStart;
    private Integer matchEnd;
}
