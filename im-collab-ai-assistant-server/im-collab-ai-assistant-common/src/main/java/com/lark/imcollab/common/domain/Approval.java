package com.lark.imcollab.common.domain;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
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
