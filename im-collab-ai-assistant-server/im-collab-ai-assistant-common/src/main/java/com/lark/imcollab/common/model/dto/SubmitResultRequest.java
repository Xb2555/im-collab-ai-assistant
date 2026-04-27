package com.lark.imcollab.common.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class SubmitResultRequest {

    private String parentCardId;
    private String status;
    private List<String> artifactRefs;
    private String rawOutput;
    private String errorMessage;
}
