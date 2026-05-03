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
public class PresentationStoryline implements Serializable {

    private String title;
    private String audience;
    private String goal;
    private String narrativeArc;
    private String style;
    private int pageCount;
    private String sourceSummary;
    private List<String> keyMessages;
}
