package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.dto.DocumentIterationRequest;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
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
public class PendingDocumentIteration implements Serializable {
    private String taskId;
    private String stepId;
    private String operatorOpenId;
    private String docId;
    private String docUrl;
    private String artifactTaskId;
    private DocumentIterationIntentType intentType;
    private DocumentEditPlan editPlan;
    private DocumentIterationRequest originalRequest;
    private Instant createdAt;
    private Instant updatedAt;
}
