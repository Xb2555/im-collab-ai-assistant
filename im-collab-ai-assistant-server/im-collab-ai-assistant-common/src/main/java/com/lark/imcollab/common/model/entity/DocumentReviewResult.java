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
public class DocumentReviewResult implements Serializable {

    private boolean backgroundCovered;

    private boolean goalCovered;

    private boolean solutionCovered;

    private boolean risksCovered;

    private boolean ownershipCovered;

    private boolean timelineCovered;

    private List<String> missingItems;

    private List<DocumentSectionDraft> supplementalSections;

    private String summary;
}
