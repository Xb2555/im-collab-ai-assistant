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
public class PresentationGenerationOptions implements Serializable {

    private int pageCount;
    private String style;
    private String themeFamily;
    private String density;
    private String audience;
    private String tone;
    private boolean speakerNotes;
    private String templateDiversity;
    private boolean allowVariantMixing;
}
