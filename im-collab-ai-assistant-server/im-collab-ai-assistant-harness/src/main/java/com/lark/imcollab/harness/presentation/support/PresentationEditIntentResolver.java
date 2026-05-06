package com.lark.imcollab.harness.presentation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationIterationIntentType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

@Component
public class PresentationEditIntentResolver implements PresentationEditIntentFacade {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public PresentationEditIntentResolver(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public PresentationEditIntent resolve(String instruction) {
        try {
            String response = chatModel.call(buildPrompt(instruction));
            JsonNode root = objectMapper.readTree(stripCodeFences(response));
            PresentationEditIntent intent = PresentationEditIntent.builder()
                    .intentType(parseIntentType(root.path("intentType").asText(null)))
                    .actionType(parseActionType(root.path("actionType").asText(null)))
                    .userInstruction(instruction)
                    .pageIndex(root.path("pageIndex").isInt() ? root.path("pageIndex").asInt() : null)
                    .replacementText(text(root, "replacementText"))
                    .clarificationNeeded(root.path("clarificationNeeded").asBoolean(false))
                    .clarificationHint(text(root, "clarificationHint"))
                    .build();
            return validate(intent);
        } catch (Exception exception) {
            return PresentationEditIntent.builder()
                    .userInstruction(instruction)
                    .clarificationNeeded(true)
                    .clarificationHint("无法准确识别这条 PPT 修改意图，请明确要改第几页和改成什么内容。")
                    .build();
        }
    }

    private PresentationEditIntent validate(PresentationEditIntent intent) {
        if (intent == null) {
            return clarification(null);
        }
        if (intent.isClarificationNeeded()) {
            return ensureHint(intent);
        }
        if (intent.getIntentType() == null || intent.getActionType() == null) {
            return clarification(intent.getUserInstruction());
        }
        if (intent.getActionType() == PresentationEditActionType.REPLACE_SLIDE_TITLE) {
            if (intent.getPageIndex() == null || !hasText(intent.getReplacementText())) {
                return clarification(intent.getUserInstruction());
            }
        }
        return intent;
    }

    private PresentationEditIntent clarification(String instruction) {
        return PresentationEditIntent.builder()
                .userInstruction(instruction)
                .clarificationNeeded(true)
                .clarificationHint("请明确要改第几页、修改哪个元素，以及改成什么内容。")
                .build();
    }

    private PresentationEditIntent ensureHint(PresentationEditIntent intent) {
        if (hasText(intent.getClarificationHint())) {
            return intent;
        }
        intent.setClarificationHint("请明确要改第几页、修改哪个元素，以及改成什么内容。");
        return intent;
    }

    private String buildPrompt(String instruction) {
        return """
                你是 PPT 编辑意图解析器。根据用户指令输出 JSON，不要解释。

                schema:
                {
                  "intentType": "UPDATE_CONTENT|INSERT|DELETE|EXPLAIN",
                  "actionType": "REPLACE_SLIDE_TITLE|REPLACE_SLIDE_BODY|INSERT_SLIDE|DELETE_SLIDE",
                  "pageIndex": 1,
                  "replacementText": "新的标题或内容",
                  "clarificationNeeded": false,
                  "clarificationHint": ""
                }

                规则:
                1. 只有在能明确识别页码和目标内容时，clarificationNeeded=false。
                2. “把第一页标题改成7878”“第一页标题为7878”“修改第3页标题为实施收益”都应解析为 REPLACE_SLIDE_TITLE。
                3. 无法确认页码、目标元素或新内容时，clarificationNeeded=true。

                用户指令：%s
                """.formatted(instruction == null ? "" : instruction.trim());
    }

    private String stripCodeFences(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            if (firstLineEnd >= 0) {
                trimmed = trimmed.substring(firstLineEnd + 1).trim();
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    private PresentationIterationIntentType parseIntentType(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return PresentationIterationIntentType.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private PresentationEditActionType parseActionType(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return PresentationEditActionType.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String text(JsonNode root, String field) {
        String value = root.path(field).asText(null);
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
