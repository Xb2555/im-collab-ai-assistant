package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
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
public class ArtifactRecord implements Serializable {

    private String artifactId;
    private String taskId;
    private String sourceStepId;
    private ArtifactTypeEnum type;
    private String title;
    private String url;
    private String preview;
    private String status;
    private String visibility;
    private String cleanupStatus;
    private int planVersion;
    private String executionAttemptId;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;
}
