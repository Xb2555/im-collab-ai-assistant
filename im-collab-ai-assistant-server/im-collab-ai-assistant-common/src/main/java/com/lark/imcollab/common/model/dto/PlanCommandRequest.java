package com.lark.imcollab.common.model.dto;

import lombok.Data;

@Data
public class PlanCommandRequest {
    private String action;
    private String feedback;
    private int version;
}
