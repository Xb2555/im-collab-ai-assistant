package com.lark.imcollab.common.model.entity;

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
public class TaskSubmissionResult implements Serializable {

    private String taskId;

    private String parentCardId;

    private String agentTaskId;

    @Builder.Default
    private String status = "COMPLETED";

    private List<String> artifactRefs;

    private String rawOutput;

    private String errorMessage;

    @Builder.Default
    private Instant submittedAt = Instant.now();
}
