package com.lark.imcollab.common.domain;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class Task implements Serializable {
    private String taskId;
    private String conversationId;
    private String userId;
    private String rawInstruction;
    private TaskType type;
    private TaskStatus status;
    private List<Step> steps;
    private List<Artifact> artifacts;
    private Instant createdAt;
    private Instant updatedAt;
    private String failReason;
}
