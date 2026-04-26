package com.lark.imcollab.planner.controller.dto;

import lombok.Data;

@Data
public class PlanRequest {
    private String rawInstruction;
    private String context;
    private String taskId;
    private String userFeedback;
}
