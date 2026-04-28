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
public class Approval implements Serializable {
    private String approvalId;
    private String taskId;
    private String stepId;
    private String prompt;
    private ApprovalStatus status;
    private String userFeedback;
    private Instant createdAt;
    private Instant decidedAt;
}
