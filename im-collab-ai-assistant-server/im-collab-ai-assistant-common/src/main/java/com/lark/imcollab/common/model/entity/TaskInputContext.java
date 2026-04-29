package com.lark.imcollab.common.model.entity;

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
@Schema(description = "任务输入上下文")
public class TaskInputContext implements Serializable {

    @Schema(description = "输入来源")
    private String inputSource;

    @Schema(description = "会话 ID")
    private String chatId;

    @Schema(description = "线程 ID")
    private String threadId;

    @Schema(description = "消息 ID")
    private String messageId;

    @Schema(description = "发送者 OpenId")
    private String senderOpenId;

    @Schema(description = "聊天类型")
    private String chatType;
}
