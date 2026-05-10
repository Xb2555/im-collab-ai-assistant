package com.lark.imcollab.common.model.vo;

import com.lark.imcollab.common.model.enums.PresentationIterationStatus;
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
    private PresentationIterationStatus status;
    private Boolean writeApplied;
    private Boolean verificationPassed;
    private String failureReason;
    private String summary;
    private List<String> modifiedSlides;
}
