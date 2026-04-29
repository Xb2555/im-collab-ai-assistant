package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.domain.Conversation;
import com.lark.imcollab.common.domain.TaskType;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IntentRouter {

    private final ChatModel chatModel;

    public TaskType route(Conversation conversation) {
        String msg = conversation.getRawMessage().toLowerCase();
        if (msg.contains("ppt") || msg.contains("幻灯片") || msg.contains("演示")) {
            return TaskType.WRITE_SLIDES;
        }
        if (msg.contains("白板") || msg.contains("流程图") || msg.contains("架构图")) {
            return TaskType.WRITE_WHITEBOARD;
        }
        if (msg.contains("文档") || msg.contains("报告")) {
            return TaskType.WRITE_DOC;
        }
        return TaskType.WRITE_DOC;
    }
}
