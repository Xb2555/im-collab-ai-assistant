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
public class DocumentPlanSection implements Serializable {

    private String sectionId;

    private int index;

    private String heading;

    private String purpose;

    private List<String> mustCover;

    private List<String> allowedSubheadings;

    private List<String> dependsOn;

    private String semanticType;

    private String riskLevel;

    private List<String> acceptanceCriteria;
}
