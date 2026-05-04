package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
public class ExecutionPlan implements Serializable {
    private List<ExecutionStep> steps;
    private boolean requiresApproval;
    private String rollbackPolicy;
    private DocumentExpectedStateType expectedState;
}
