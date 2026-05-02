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
public class DocumentCompletenessReport implements Serializable {

    private List<DocumentSectionCompleteness> sections;

    private List<String> incompleteSectionHeadings;

    private List<String> unmatchedSupplementalHeadings;

    private List<String> issues;
}
