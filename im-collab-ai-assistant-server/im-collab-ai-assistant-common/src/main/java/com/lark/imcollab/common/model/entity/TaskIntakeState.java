package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
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
@Schema(description = "任务入口状态")
public class TaskIntakeState implements Serializable {

    @Schema(description = "入口判定类型")
    private TaskIntakeTypeEnum intakeType;

    @Schema(description = "是否续接已有会话")
    private boolean continuedConversation;

    @Schema(description = "会话绑定键")
    private String continuationKey;

    @Schema(description = "最近一次用户输入")
    private String lastUserMessage;

    @Schema(description = "意图识别说明")
    private String routingReason;

    @Schema(description = "无法处理当前意图时面向用户的自然回复")
    private String assistantReply;

    @Schema(description = "只读查询视图，由 LLM 在固定枚举意图内细分，例如 PLAN/STATUS/ARTIFACTS")
    private String readOnlyView;

    @Schema(description = "最近一次输入时间")
    private String lastInputAt;
}
