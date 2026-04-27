package com.lark.imcollab.planner.controller.dto;

import lombok.Data;

@Data
public class PlanCommandRequest {
    private String action;
    private String feedback;
    private int version;
}
