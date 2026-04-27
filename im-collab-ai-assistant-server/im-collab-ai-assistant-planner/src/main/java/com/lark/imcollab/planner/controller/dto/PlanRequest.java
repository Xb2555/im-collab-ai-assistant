package com.lark.imcollab.planner.controller.dto;

import com.lark.imcollab.common.model.entity.WorkspaceContext;
import lombok.Data;

@Data
public class PlanRequest {
    private String rawInstruction;
    private String taskId;
    private String userFeedback;
    private WorkspaceContext workspaceContext;
}
