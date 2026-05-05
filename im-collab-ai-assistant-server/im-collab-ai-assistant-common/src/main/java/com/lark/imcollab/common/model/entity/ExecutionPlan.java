package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.DocumentExpectedStateType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPlan implements Serializable {
    private List<ExecutionStep> steps;
    private boolean requiresApproval;
    private String rollbackPolicy;
    private DocumentExpectedStateType expectedState;
}
