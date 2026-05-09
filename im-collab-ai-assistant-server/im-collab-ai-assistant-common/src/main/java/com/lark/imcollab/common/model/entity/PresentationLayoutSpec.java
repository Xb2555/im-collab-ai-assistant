package com.lark.imcollab.common.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresentationLayoutSpec implements Serializable {
    private Integer topLeftX;
    private Integer topLeftY;
    private Integer width;
    private Integer height;
    private String templateVariant;
    private String backgroundStyle;
    private String accentStyle;
}
