package com.lark.imcollab.harness.presentation.model;

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
public class PresentationReviewResult implements Serializable {

    private boolean accepted;
    private List<String> missingItems;
    private List<String> problemSlideIds;
    private String summary;
    private String revisionAdvice;
}
