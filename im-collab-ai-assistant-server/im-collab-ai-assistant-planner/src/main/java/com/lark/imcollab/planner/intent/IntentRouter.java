package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.domain.Conversation;
import com.lark.imcollab.common.domain.TaskType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class IntentRouter {

    private final LlmChoiceResolver llmChoiceResolver;

    public TaskType route(Conversation conversation) {
        String msg = conversation.getRawMessage().toLowerCase(Locale.ROOT);
        boolean wantsSlides = containsAny(msg, "ppt", "幻灯片", "演示", "slides");
        boolean wantsDoc = containsAny(msg, "文档", "报告", "方案", "纪要", "prd", "文章");
        boolean wantsBoard = containsAny(msg, "白板", "流程图", "架构图", "脑图", "mermaid", "whiteboard");
        if ((wantsSlides && wantsDoc) || (wantsSlides && wantsBoard) || (wantsDoc && wantsBoard)) {
            return TaskType.MIXED;
        }
        if (wantsSlides) {
            return TaskType.WRITE_SLIDES;
        }
        if (wantsBoard) {
            return TaskType.WRITE_WHITEBOARD;
        }
        if (wantsDoc) {
            return TaskType.WRITE_DOC;
        }

        String choice = llmChoiceResolver.chooseOne(
                conversation.getRawMessage(),
                List.of(TaskType.WRITE_DOC.name(), TaskType.WRITE_SLIDES.name(), TaskType.WRITE_WHITEBOARD.name()),
                """
                你负责识别用户此轮最主要的任务类型。
                只能从给定的 TaskType 枚举值中选择一个：
                - WRITE_DOC：文档、方案、报告、纪要、PRD
                - WRITE_SLIDES：PPT、演示稿、汇报页、幻灯片
                - WRITE_WHITEBOARD：白板、流程图、架构图、脑图
                若不确定，优先选择最贴近用户显式交付物的一个，不要返回 MIXED。
                """
        );
        try {
            return TaskType.valueOf(choice);
        } catch (IllegalArgumentException ignored) {
            return TaskType.WRITE_DOC;
        }
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
