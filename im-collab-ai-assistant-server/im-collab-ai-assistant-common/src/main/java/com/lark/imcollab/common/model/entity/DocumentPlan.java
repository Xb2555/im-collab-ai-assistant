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
public class DocumentPlan implements Serializable {

    private String planId;

    private String taskId;

    private String title;

    private String templateType;

    private List<DocumentPlanSection> orderedSections;

    private List<String> globalStyleGuide;

    private List<String> terminologyRules;

    private List<String> requiredArtifacts;

    private String diagramPlan;

    private long version;
}
