package com.lark.imcollab.harness.presentation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresentationSlideXml implements Serializable {

    private String slideId;
    private int index;
    private String title;
    private String xml;
    private String speakerNotes;
}
