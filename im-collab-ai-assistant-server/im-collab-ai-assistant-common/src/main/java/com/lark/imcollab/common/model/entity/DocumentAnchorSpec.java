package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.DocumentAnchorMatchMode;
import com.lark.imcollab.common.model.enums.DocumentAnchorKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentAnchorSpec implements Serializable {
    private DocumentAnchorKind anchorKind;
    private DocumentAnchorMatchMode matchMode;
    private String blockId;
    private String headingTitle;
    private String headingNumber;
    private String outlinePath;
    private String outlinePathText;
    private String outlinePathNumbers;
    private String parentHeadingTitle;
    private String parentHeadingNumber;
    private Integer structuralOrdinal;
    private String structuralOrdinalScope;
    private String quotedText;
    private String mediaCaption;
    private String relativePosition;
    private String scopeHint;
}
