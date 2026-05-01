package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public class DocumentIterationIntentService {

    private final ChatModel chatModel;

    public DocumentIterationIntentService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public DocumentIterationIntentType resolve(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "instruction must be provided");
        }
        String response = chatModel.call(buildPrompt(instruction));
        String normalized = response == null ? "" : response.trim();
        try {
            return DocumentIterationIntentType.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR,
                    "无法识别文档迭代意图，模型返回了非法枚举值: " + normalized);
        }
    }

    private String buildPrompt(String instruction) {
        String allowed = Arrays.stream(DocumentIterationIntentType.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        return """
                你是文档迭代意图分类器。
                你的任务是根据用户指令，只返回一个枚举值，且必须严格从下列候选中选择：
                %s

                判定规则：
                1. 只能返回一个枚举值本身，不要解释，不要 JSON，不要代码块。
                2. 如果用户要求解释、说明、阐述文档内容，返回 EXPLAIN。
                3. 如果用户要求新增内容，返回 INSERT。
                4. 如果用户要求改写事实内容，返回 UPDATE_CONTENT。
                5. 如果用户要求只调整语气、风格、正式度、表达方式，返回 UPDATE_STYLE。
                6. 如果用户要求删除、去掉、移除某段或某节，返回 DELETE。
                7. 如果用户要求插入图片、附件、表格、白板等富媒体，返回 INSERT_MEDIA。
                8. 如果用户要求调整布局、层级、顺序、结构，返回 ADJUST_LAYOUT。

                用户指令：
                %s
                """.formatted(allowed, instruction);
    }
}
