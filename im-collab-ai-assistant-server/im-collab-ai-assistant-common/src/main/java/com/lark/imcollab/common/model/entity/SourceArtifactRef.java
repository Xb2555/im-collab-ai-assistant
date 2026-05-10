package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "作为当前任务输入来源的前序产物引用")
public class SourceArtifactRef implements Serializable {

    @Schema(description = "产物 ID")
    private String artifactId;

    @Schema(description = "来源任务 ID")
    private String taskId;

    @Schema(description = "产物类型")
    private ArtifactTypeEnum artifactType;

    @Schema(description = "产物标题")
    private String title;

    @Schema(description = "产物链接")
    private String url;

    @Schema(description = "产物预览")
    private String preview;

    @Schema(description = "引用用途（PRIMARY_SOURCE/REFERENCE）")
    private String usage;
}
