package com.lark.imcollab.common.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "图表需求")
public class DiagramRequirement implements Serializable {

    @Schema(description = "是否要求图表")
    private boolean required;

    @Schema(description = "图表类型列表（如 DATA_FLOW/SEQUENCE/STATE/CONTEXT）")
    private List<String> types;

    @Schema(description = "图表格式（如 MERMAID）")
    private String format;

    @Schema(description = "图表放置方式（如 INLINE_DOC）")
    private String placement;

    @Schema(description = "图表数量")
    private int count;
}
