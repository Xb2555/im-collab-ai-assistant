package com.lark.imcollab.harness.document.iteration.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DocumentEditIntentResolver {

    private final DocumentIterationIntentService intentService;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public DocumentEditIntentResolver(
            DocumentIterationIntentService intentService,
            ChatModel chatModel,
            ObjectMapper objectMapper
    ) {
        this.intentService = intentService;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    public DocumentEditIntent resolve(String instruction) {
        DocumentIterationIntentType intentType = intentService.resolve(instruction);
        Map<String, String> parameters = extractParameters(instruction, intentType);
        DocumentSemanticActionType semanticAction = resolveSemanticActionViaLlm(instruction, intentType);
        return DocumentEditIntent.builder()
                .intentType(intentType)
                .semanticAction(semanticAction)
                .userInstruction(instruction)
                .parameters(parameters)
                .build();
    }

    private DocumentSemanticActionType resolveSemanticActionViaLlm(String instruction, DocumentIterationIntentType intentType) {
        try {
            String response = chatModel.call(buildSemanticActionPrompt(instruction, intentType)).trim();
            return DocumentSemanticActionType.valueOf(response.trim());
        } catch (Exception ignored) {
            return defaultSemanticAction(intentType);
        }
    }

    private DocumentSemanticActionType defaultSemanticAction(DocumentIterationIntentType intentType) {
        return switch (intentType) {
            case EXPLAIN -> DocumentSemanticActionType.EXPLAIN_ONLY;
            case INSERT, INSERT_MEDIA -> DocumentSemanticActionType.INSERT_BLOCK_AFTER_ANCHOR;
            case UPDATE_CONTENT, UPDATE_STYLE -> DocumentSemanticActionType.REWRITE_INLINE_TEXT;
            case DELETE -> DocumentSemanticActionType.DELETE_INLINE_TEXT;
            case ADJUST_LAYOUT -> DocumentSemanticActionType.MOVE_BLOCK;
        };
    }

    private Map<String, String> extractParameters(String instruction, DocumentIterationIntentType intentType) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("instruction", instruction == null ? "" : instruction.trim());
        try {
            String response = chatModel.call(buildSlotPrompt(instruction, intentType));
            Map<String, Object> payload = objectMapper.readValue(response, new TypeReference<>() {});
            parameters.put("targetRegion", stringify(payload.get("targetRegion")));
            parameters.put("targetSemantic", stringify(payload.get("targetSemantic")));
            parameters.put("targetKeywords", joinKeywords(payload.get("targetKeywords")));
        } catch (Exception ignored) {
            parameters.put("targetRegion", "unknown");
            parameters.put("targetSemantic", "unknown");
            parameters.put("targetKeywords", "");
        }
        return parameters;
    }

    private String buildSemanticActionPrompt(String instruction, DocumentIterationIntentType intentType) {
        String validValues = Arrays.stream(DocumentSemanticActionType.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        return "你是文档编辑语义动作分类器。\n"
                + "根据用户指令和高层意图，从以下枚举值中选择最匹配的一个，只输出枚举名，不要解释。\n\n"
                + "可选值：\n" + validValues + "\n\n"
                + "intentType: " + (intentType == null ? "unknown" : intentType.name()) + "\n"
                + "用户指令: " + (instruction == null ? "" : instruction);
    }

    private String buildSlotPrompt(String instruction, DocumentIterationIntentType intentType) {
        return "你是文档编辑参数槽位提取器。\n"
                + "请基于用户指令和已识别的高层意图，输出一个 JSON 对象，不要输出解释。\n\n"
                + "输出 schema:\n"
                + "{\n"
                + "  \"targetRegion\": \"document_head|document_tail|section_body|inline|unknown\",\n"
                + "  \"targetSemantic\": \"metadata|section|paragraph|block|unknown\",\n"
                + "  \"targetKeywords\": [\"...\"]\n"
                + "}\n\n"
                + "规则:\n"
                + "1. 只输出合法 JSON。\n"
                + "2. targetRegion 表示目标所在结构区域，不是 patch 类型。\n"
                + "3. targetSemantic 表示目标对象语义。\n"
                + "4. targetKeywords 只保留用户真正要命中的对象关键词，去掉动作词。\n"
                + "5. 无法确定时用 unknown，不要臆测。\n\n"
                + "intentType: " + (intentType == null ? "unknown" : intentType.name()) + "\n"
                + "用户指令: " + (instruction == null ? "" : instruction);
    }

    private String stringify(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String joinKeywords(Object value) {
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).distinct()
                    .collect(Collectors.joining("|"));
        }
        return "";
    }
}
