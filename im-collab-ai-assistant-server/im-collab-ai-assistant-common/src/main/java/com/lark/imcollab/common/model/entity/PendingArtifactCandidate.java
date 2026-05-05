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
public class PendingArtifactCandidate implements Serializable {

    private String artifactId;
    private String taskId;
    private ArtifactTypeEnum type;
    private String title;
    private String url;
    private String status;
    private int version;
    private Instant updatedAt;
}
