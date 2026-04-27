package com.lark.imcollab.common.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "任务结果提交请求")
public class SubmitResultRequest {

    @Schema(description = "父任务卡片ID")
    private String parentCardId;

    @Schema(description = "任务状态（COMPLETED/FAILED）")
    private String status;

    @Schema(description = "产出物引用列表（文档链接、文件ID等）")
    private List<String> artifactRefs;

    @Schema(description = "原始输出内容")
    private String rawOutput;

    @Schema(description = "错误信息（如有）")
    private String errorMessage;
}
