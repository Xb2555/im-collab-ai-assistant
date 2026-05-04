package com.lark.imcollab.common.model.entity;

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
public class ExecutionStep implements Serializable {
    private String stepId;
    private String stepType;
    private Object input;
    private Object output;
    private String toolBinding;
    private String failureMode;
}
