package com.lark.imcollab.planner.controller.dto;

import lombok.Data;

@Data
public class ResumeRequest {
    private String feedback;
    private boolean replanFromRoot;
}
