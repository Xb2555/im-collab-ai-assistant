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
public class DocumentSectionCompleteness implements Serializable {

    private String sectionId;

    private String heading;

    private boolean rendered;

    private boolean hasBody;

    private boolean meaningfulContent;

    private boolean diagramSection;

    private boolean diagramPresent;

    private boolean reviewSupplemented;

    private List<String> issues;
}
