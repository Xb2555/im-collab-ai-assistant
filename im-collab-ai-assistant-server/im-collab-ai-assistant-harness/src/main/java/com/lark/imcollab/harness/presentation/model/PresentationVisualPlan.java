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
public class PresentationVisualPlan implements Serializable {

    private List<SlideVisualSpec> slides;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlideVisualSpec implements Serializable {
        private String slideId;
        private String templateVariant;
        private String density;
        private int imageSlots;
        private int chartSlots;
        private String backgroundStyle;
        private String accentStyle;
    }
}
