package com.lark.imcollab.common.model.dto;

import lombok.Data;

@Data
public class ResumeRequest {
    private String feedback;
    private boolean replanFromRoot;
}
