package com.lark.imcollab.common.model.enums;

public enum LoopMessageTypeEnum {
    /**
     * 用户输入消息
     */
    USER,
    /**
     * 模型回复消息
     */
    ASSISTANT,
    /**
     * 模型发起工具调用
     */
    TOOL_USE,
    /**
     * 工具执行返回结果
     */
    TOOL_RESULT
}
