package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingTaskCandidate implements Serializable {

    private String taskId;
    private String title;
    private String goal;
    private List<ArtifactTypeEnum> artifactTypes;
    private Instant updatedAt;
}
