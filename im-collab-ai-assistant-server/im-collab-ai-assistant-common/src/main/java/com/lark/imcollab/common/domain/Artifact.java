package com.lark.imcollab.common.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Artifact implements Serializable {
    private String artifactId;
    private String taskId;
    private String stepId;
    private ArtifactType type;
    private String title;
    private String content;
    private String documentId;
    private String externalUrl;
    private String ownerScenario;
    private boolean createdBySystem;
    private String lastEditedBy;
    private Instant lastEditedAt;
    private Instant createdAt;
}
