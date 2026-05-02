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
public class DocumentSectionDraft implements Serializable {

    private String sectionId;

    private String heading;

    private String body;

    private String inputSummary;

    private java.util.List<String> upstreamDependencies;

    private String status;

    private java.util.List<String> qualityFlags;

    private long version;
}
