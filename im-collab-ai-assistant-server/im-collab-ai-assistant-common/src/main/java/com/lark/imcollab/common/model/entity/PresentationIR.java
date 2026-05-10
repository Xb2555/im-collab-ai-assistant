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
public class PresentationIR implements Serializable {
    private String title;
    private String themeFamily;
    private String styleMode;
    private Integer width;
    private Integer height;
    private List<PresentationSlideIR> slides;
}
