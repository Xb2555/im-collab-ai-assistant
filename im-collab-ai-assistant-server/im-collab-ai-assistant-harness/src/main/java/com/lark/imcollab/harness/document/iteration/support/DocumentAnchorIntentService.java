package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentLocatorStrategy;
import com.lark.imcollab.common.model.enums.DocumentRelativePosition;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

@Deprecated
public class DocumentAnchorIntentService {

    private final ChatModel chatModel;

    public DocumentAnchorIntentService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public AnchorDecision decide(DocumentIterationIntentType intentType, String instruction) {
        if (intentType == null) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "intentType must be provided");
        }
        if (instruction == null || instruction.isBlank()) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "instruction must be provided");
        }
        String response = chatModel.call(buildPrompt(intentType, instruction));
        return parse(response);
    }

    private String buildPrompt(DocumentIterationIntentType intentType, String instruction) {
        String locatorEnums = Arrays.stream(DocumentLocatorStrategy.values()).map(Enum::name).collect(Collectors.joining(", "));
        String relativeEnums = Arrays.stream(DocumentRelativePosition.values()).map(Enum::name).collect(Collectors.joining(", "));
        return """
                你是文档锚点定位分类器。
                你的任务是根据用户指令和已知意图，输出一个严格的三行结果：
                LOCATOR_STRATEGY=<枚举值>
                RELATIVE_POSITION=<枚举值>
                LOCATOR_VALUE=<文本，可为空>

                约束：
                1. LOCATOR_STRATEGY 只能从这些值中选择：%s
                2. RELATIVE_POSITION 只能从这些值中选择：%s
                3. 只能输出这三行，不要解释，不要 JSON，不要代码块。
                4. 如果用户是“在文档开头新增”，使用 DOC_START + BEFORE，LOCATOR_VALUE 留空。
                5. 如果用户是“在文档末尾新增”，使用 DOC_END + AFTER，LOCATOR_VALUE 留空。
                6. 如果用户明确提到章节标题，优先使用 BY_HEADING，并把标题文本放入 LOCATOR_VALUE。
                7. 如果用户引用了原文片段，优先使用 BY_EXACT_TEXT，并把原文片段放入 LOCATOR_VALUE。
                8. DELETE 意图返回 DELETE；UPDATE_* 意图通常返回 REPLACE；INSERT 意图通常返回 BEFORE 或 AFTER。

                已知意图：
                %s

                用户指令：
                %s
                """.formatted(locatorEnums, relativeEnums, intentType.name(), instruction);
    }

    private AnchorDecision parse(String response) {
        if (response == null || response.isBlank()) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "模型未返回文档锚点定位结果");
        }
        String locator = null;
        String relative = null;
        String value = "";
        for (String rawLine : response.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("LOCATOR_STRATEGY=")) {
                locator = line.substring("LOCATOR_STRATEGY=".length()).trim();
            } else if (line.startsWith("RELATIVE_POSITION=")) {
                relative = line.substring("RELATIVE_POSITION=".length()).trim();
            } else if (line.startsWith("LOCATOR_VALUE=")) {
                value = line.substring("LOCATOR_VALUE=".length()).trim();
            }
        }
        try {
            return new AnchorDecision(
                    DocumentLocatorStrategy.valueOf(normalize(locator)),
                    DocumentRelativePosition.valueOf(normalize(relative)),
                    value
            );
        } catch (RuntimeException exception) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR,
                    "模型返回了非法锚点定位结果: " + response);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public record AnchorDecision(
            DocumentLocatorStrategy locatorStrategy,
            DocumentRelativePosition relativePosition,
            String locatorValue
    ) {
    }
}
