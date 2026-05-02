package com.lark.imcollab.common.model.dto;

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
@Schema(description = "文档迭代审批请求")
public class DocumentIterationApprovalRequest implements Serializable {

    @Schema(description = "审批动作：APPROVE / REJECT / MODIFY", requiredMode = Schema.RequiredMode.REQUIRED)
    private String action;

    @Schema(description = "审批反馈或修改意见")
    private String feedback;
}
