package com.lark.imcollab.common.domain;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
public class Artifact implements Serializable {
    private String artifactId;
    private String taskId;
    private String stepId;
    private ArtifactType type;
    private String title;
    private String content;
    private String externalUrl;
    private Instant createdAt;
}
