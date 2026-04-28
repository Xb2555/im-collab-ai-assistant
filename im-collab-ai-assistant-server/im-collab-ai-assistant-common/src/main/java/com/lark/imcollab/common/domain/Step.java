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
public class Step implements Serializable {
    private String stepId;
    private String taskId;
    private String name;
    private StepStatus status;
    private String input;
    private String output;
    private int retryCount;
    private Instant createdAt;
    private Instant updatedAt;
    private String failReason;
}
