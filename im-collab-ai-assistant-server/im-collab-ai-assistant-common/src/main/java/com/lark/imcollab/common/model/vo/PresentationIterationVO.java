package com.lark.imcollab.common.model.vo;

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
public class PresentationIterationVO implements Serializable {

    private String taskId;
    private String artifactId;
    private String presentationId;
    private String presentationUrl;
    private String summary;
    private List<String> modifiedSlides;
}
