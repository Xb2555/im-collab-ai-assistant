package com.lark.imcollab.common.model.dto;

import com.lark.imcollab.common.model.entity.WorkspaceContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "任务规划请求")
public class PlanRequest {

    @Schema(description = "用户原始指令（自然语言描述）")
    private String rawInstruction;

    @Schema(description = "语音指令占位契约。当前后端文本优先，收到语音时会保留字段但不会直接转写")
    private VoiceInstruction voiceInstruction;

    @Schema(description = "任务ID（首次创建时为空）")
    private String taskId;

    @Schema(description = "用户反馈/澄清回答")
    private String userFeedback;

    @Schema(description = "工作空间上下文（选中的消息、时间范围等）")
    private WorkspaceContext workspaceContext;

    @Data
    @Schema(description = "语音指令")
    public static class VoiceInstruction {

        @Schema(description = "语音格式，当前建议 base64")
        private String format;

        @Schema(description = "语音载荷")
        private String payload;
    }
}
