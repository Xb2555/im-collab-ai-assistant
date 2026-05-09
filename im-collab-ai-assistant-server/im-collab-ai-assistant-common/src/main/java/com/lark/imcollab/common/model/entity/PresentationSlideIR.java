package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PresentationEditability;
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
public class PresentationSlideIR implements Serializable {
    private String slideId;
    private Integer pageIndex;
    private String slideRole;
    private String title;
    private String message;
    private String visualIntent;
    private PresentationEditability editability;
    private List<PresentationElementIR> elements;
}
