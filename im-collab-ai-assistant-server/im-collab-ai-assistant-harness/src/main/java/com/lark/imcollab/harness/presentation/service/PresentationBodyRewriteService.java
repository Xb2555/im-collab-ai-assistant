package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.model.entity.PresentationEditOperation;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class PresentationBodyRewriteService {

    private final ChatModel chatModel;

    public PresentationBodyRewriteService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String rewrite(String originalText, PresentationEditOperation operation) {
        String source = trim(originalText);
        String instruction = trim(operation == null ? null : operation.getContentInstruction());
        String quotedText = trim(operation == null ? null : operation.getQuotedText());
        PresentationEditActionType actionType = operation == null ? null : operation.getActionType();
        String generated = trim(chatModel.call("""
                你是企业 PPT 正文精修助手。输出可直接替换回幻灯片正文的一段纯文本，不要解释，不要标题，不要代码块。
                任务目标：%s
                改写规则：%s
                额外要求：
                1. 保持原段落主题连续，不要改成只剩锚点短语。
                2. 可以做适度补强和表达优化，但不要引入原文中无法支撑的具体数据、年份、机构结论或外部事实。
                3. 如果用户要求写详细一些，就在原段基础上自然展开；如果要求精简，就压缩但保留核心信息；如果要求改写，就重组表达但保持语义一致。
                4. 输出只包含最终替换文本本身。

                用户指令：%s
                命中锚点：%s
                原段落：%s
                """.formatted(
                rewriteGoal(actionType),
                rewriteRule(actionType),
                safe(instruction),
                safe(quotedText),
                safe(source))));
        validateGeneratedText(source, quotedText, generated, actionType);
        return generated;
    }

    private void validateGeneratedText(
            String originalText,
            String quotedText,
            String generated,
            PresentationEditActionType actionType
    ) {
        if (!hasText(generated)) {
            throw new IllegalStateException("PPT 正文改写结果为空");
        }
        if (hasText(quotedText) && generated.equals(quotedText)) {
            throw new IllegalStateException("PPT 正文改写结果退化为锚点短语，已拒绝执行");
        }
        if ((actionType == PresentationEditActionType.EXPAND_ELEMENT
                || actionType == PresentationEditActionType.SHORTEN_ELEMENT
                || actionType == PresentationEditActionType.REWRITE_ELEMENT)
                && hasText(originalText)
                && generated.equals(originalText)) {
            throw new IllegalStateException("PPT 正文改写结果未产生有效变化");
        }
    }

    private String rewriteGoal(PresentationEditActionType actionType) {
        if (actionType == PresentationEditActionType.EXPAND_ELEMENT) {
            return "在原段落基础上扩写正文";
        }
        if (actionType == PresentationEditActionType.SHORTEN_ELEMENT) {
            return "在保留核心信息的前提下压缩正文";
        }
        return "根据用户要求重写正文";
    }

    private String rewriteRule(PresentationEditActionType actionType) {
        if (actionType == PresentationEditActionType.EXPAND_ELEMENT) {
            return "允许适度补强、增加上下文衔接和说明句，但不杜撰新事实";
        }
        if (actionType == PresentationEditActionType.SHORTEN_ELEMENT) {
            return "压缩冗余表达，保留主题、结论和关键限定";
        }
        return "允许在原意范围内改写、重组和润色，但不偏离原主题";
    }

    private String safe(String value) {
        return hasText(value) ? value : "无";
    }

    private String trim(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
