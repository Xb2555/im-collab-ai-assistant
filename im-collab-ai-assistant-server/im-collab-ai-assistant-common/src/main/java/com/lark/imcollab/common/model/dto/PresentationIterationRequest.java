package com.lark.imcollab.common.model.dto;

import com.lark.imcollab.common.model.entity.WorkspaceContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresentationIterationRequest implements Serializable {

    private String taskId;
    private String artifactId;
    private String presentationId;
    private String presentationUrl;
    private String instruction;
    private String operatorOpenId;
    private WorkspaceContext workspaceContext;
}
