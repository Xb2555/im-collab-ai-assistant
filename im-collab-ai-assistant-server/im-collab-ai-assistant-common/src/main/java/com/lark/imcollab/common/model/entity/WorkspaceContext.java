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
@Schema(description = "工作空间上下文（选中的消息、文档等）")
public class WorkspaceContext implements Serializable {

    @Schema(description = "选中内容类型（MESSAGE/DOCUMENT/FILE）")
    private String selectionType;

    @Schema(description = "时间范围筛选")
    private String timeRange;

    @Schema(description = "选中的消息内容列表")
    private List<String> selectedMessages;
}
