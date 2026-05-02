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
public class ContextAcquisitionPlan implements Serializable {

    private boolean needCollection;

    private List<ContextSourceRequest> sources;

    private String reason;

    private String clarificationQuestion;
}
