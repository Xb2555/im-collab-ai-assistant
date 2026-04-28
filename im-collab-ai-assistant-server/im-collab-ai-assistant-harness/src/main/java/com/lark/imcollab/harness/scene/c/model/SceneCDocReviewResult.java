package com.lark.imcollab.harness.scene.c.model;

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
public class SceneCDocReviewResult implements Serializable {

    private boolean backgroundCovered;

    private boolean goalCovered;

    private boolean solutionCovered;

    private boolean risksCovered;

    private boolean ownershipCovered;

    private boolean timelineCovered;

    private List<String> missingItems;

    private List<SceneCDocSectionDraft> supplementalSections;

    private String summary;
}
