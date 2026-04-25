package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.LoopMessageTypeEnum;
import lombok.Data;

@Data
public class LoopMessage {
    /** 消息类型 */
    private LoopMessageTypeEnum loopMessageTypeEnum;
    /** 消息内容 */
    private String content;
    /** 工具调用 ID（TOOL_USE/TOOL_RESULT 关联） */
    private String toolUseId;
}
