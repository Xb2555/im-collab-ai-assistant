package com.lark.imcollab.common.domain;

import com.lark.imcollab.common.model.entity.ExecutionContract;
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
public class Task implements Serializable {
    private String taskId;
    private String conversationId;
    private String userId;
    private String rawInstruction;
    private String clarifiedInstruction;
    private String taskBrief;
    private ExecutionContract executionContract;
    private TaskType type;
    private TaskStatus status;
    private List<Step> steps;
    private List<Artifact> artifacts;
    private Instant createdAt;
    private Instant updatedAt;
    private String failReason;
}
