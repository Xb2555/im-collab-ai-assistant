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
public class PresentationSlidePlan implements Serializable {

    private String slideId;
    private int index;
    private String title;
    private List<String> keyPoints;
    private String layout;
    private String templateVariant;
    private String visualEmphasis;
    private String speakerNotes;
}
