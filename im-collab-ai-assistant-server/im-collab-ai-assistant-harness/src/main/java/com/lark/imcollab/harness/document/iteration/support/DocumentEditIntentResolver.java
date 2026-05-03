package com.lark.imcollab.harness.document.iteration.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
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
        return DocumentEditIntent.builder()
                .intentType(intentType)
                .semanticAction(resolveSemanticAction(intentType, instruction, parameters))
                .userInstruction(instruction)
                .parameters(parameters)
                .build();
    }

    private DocumentSemanticActionType resolveSemanticAction(
            DocumentIterationIntentType intentType,
            String instruction,
            Map<String, String> parameters
    ) {
        String normalized = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
        String targetRegion = parameters == null ? "" : parameters.getOrDefault("targetRegion", "");
        String targetSemantic = parameters == null ? "" : parameters.getOrDefault("targetSemantic", "");
        return switch (intentType) {
            case EXPLAIN -> DocumentSemanticActionType.EXPLAIN_ONLY;
            case INSERT -> {
                if (containsAny(normalized, "文档开头", "文章开头", "最前面", "开头")) {
                    yield DocumentSemanticActionType.INSERT_METADATA_AT_DOCUMENT_HEAD;
                }
                if (containsAny(normalized, "前插", "之前插入", "前新增", "前面新增", "在", "前新增章节")
                        && containsAny(normalized, "章节", "一节", "一章", "section")) {
                    yield DocumentSemanticActionType.INSERT_SECTION_BEFORE_SECTION;
                }
                if (containsAny(normalized, "末尾", "结尾", "最后")) {
                    yield DocumentSemanticActionType.APPEND_SECTION_TO_DOCUMENT_END;
                }
                yield DocumentSemanticActionType.INSERT_BLOCK_AFTER_ANCHOR;
            }
            case UPDATE_CONTENT, UPDATE_STYLE -> "document_head".equals(targetRegion) && "metadata".equals(targetSemantic)
                    ? DocumentSemanticActionType.REWRITE_METADATA_AT_DOCUMENT_HEAD
                    : containsAny(normalized, "章节", "小节", "整节", "正文")
                    ? DocumentSemanticActionType.REWRITE_SECTION_BODY
                    : DocumentSemanticActionType.REWRITE_INLINE_TEXT;
            case DELETE -> containsAny(normalized, "开头", "文首", "最前面", "作者", "作者信息", "署名", "负责人")
                    ? DocumentSemanticActionType.DELETE_METADATA_AT_DOCUMENT_HEAD
                    : containsAny(normalized, "整节", "整个章节", "整章")
                    ? DocumentSemanticActionType.DELETE_WHOLE_SECTION
                    : containsAny(normalized, "正文", "内容")
                    ? DocumentSemanticActionType.DELETE_SECTION_BODY
                    : DocumentSemanticActionType.DELETE_INLINE_TEXT;
            case ADJUST_LAYOUT -> containsAny(normalized, "章节", "顺序", "位置")
                    ? DocumentSemanticActionType.MOVE_SECTION
                    : DocumentSemanticActionType.MOVE_BLOCK;
            case INSERT_MEDIA -> DocumentSemanticActionType.INSERT_BLOCK_AFTER_ANCHOR;
        };
    }

    private Map<String, String> extractParameters(String instruction, DocumentIterationIntentType intentType) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("instruction", instruction == null ? "" : instruction.trim());
        Map<String, String> slots = resolveStructuredSlots(instruction, intentType);
        parameters.put("targetRegion", slots.getOrDefault("targetRegion", ""));
        parameters.put("targetSemantic", slots.getOrDefault("targetSemantic", ""));
        parameters.put("targetKeywords", slots.getOrDefault("targetKeywords", ""));
        return parameters;
    }

    private Map<String, String> resolveStructuredSlots(String instruction, DocumentIterationIntentType intentType) {
        try {
            String response = chatModel.call(buildSlotPrompt(instruction, intentType));
            Map<String, Object> payload = objectMapper.readValue(response, new TypeReference<>() {});
            String targetRegion = stringify(payload.get("targetRegion"));
            String targetSemantic = stringify(payload.get("targetSemantic"));
            String targetKeywords = joinKeywords(payload.get("targetKeywords"));
            Map<String, String> slots = new LinkedHashMap<>();
            slots.put("targetRegion", targetRegion);
            slots.put("targetSemantic", targetSemantic);
            slots.put("targetKeywords", targetKeywords);
            return slots;
        } catch (Exception ignored) {
            return fallbackStructuredSlots(instruction);
        }
    }

    private Map<String, String> fallbackStructuredSlots(String instruction) {
        Map<String, String> slots = new LinkedHashMap<>();
        String normalized = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
        slots.put("targetRegion", normalized.contains("开头") || normalized.contains("文首") || normalized.contains("最前面")
                ? "document_head" : "");
        slots.put("targetSemantic", "");
        slots.put("targetKeywords", String.join("|", fallbackKeywords(instruction)));
        return slots;
    }

    private List<String> fallbackKeywords(String instruction) {
        if (instruction == null || instruction.isBlank()) {
            return List.of();
        }
        return Arrays.stream(instruction.split("[，。；、\\s]+"))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .filter(token -> !List.of("删除", "开头", "文首", "最前面", "信息", "的").contains(token))
                .distinct()
                .collect(Collectors.toList());
    }

    private String buildSlotPrompt(String instruction, DocumentIterationIntentType intentType) {
        return """
                你是文档编辑参数槽位提取器。
                请基于用户指令和已识别的高层意图，输出一个 JSON 对象，不要输出解释。

                输出 schema:
                {
                  "targetRegion": "document_head|document_tail|section_body|inline|unknown",
                  "targetSemantic": "metadata|section|paragraph|block|unknown",
                  "targetKeywords": ["..."]
                }

                规则:
                1. 只输出合法 JSON。
                2. `targetRegion` 表示目标所在结构区域，不是 patch 类型。
                3. `targetSemantic` 表示目标对象语义，不要输出业务解释。
                4. `targetKeywords` 只保留用户真正要命中的对象关键词，去掉“删除/修改/新增”等动作词。
                5. 无法确定时用 `unknown`，不要臆测。

                intentType:
                %s

                用户指令:
                %s
                """.formatted(intentType == null ? "unknown" : intentType.name(), instruction == null ? "" : instruction);
    }

    private String stringify(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String joinKeywords(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).distinct()
                    .collect(Collectors.joining("|"));
        }
        return "";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
