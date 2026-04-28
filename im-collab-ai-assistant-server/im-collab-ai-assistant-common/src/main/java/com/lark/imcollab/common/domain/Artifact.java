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
    private String externalUrl;
    private Instant createdAt;
}
