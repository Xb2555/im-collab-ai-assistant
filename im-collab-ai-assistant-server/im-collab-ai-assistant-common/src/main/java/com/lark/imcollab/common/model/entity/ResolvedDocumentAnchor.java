package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.DocumentAnchorType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolvedDocumentAnchor implements Serializable {
    private DocumentAnchorType anchorType;
    private DocumentTextAnchor textAnchor;
    private DocumentBlockAnchor blockAnchor;
    private DocumentSectionAnchor sectionAnchor;
    private DocumentMediaAnchor mediaAnchor;
    private String preview;
    private String insertionBlockId;
}
