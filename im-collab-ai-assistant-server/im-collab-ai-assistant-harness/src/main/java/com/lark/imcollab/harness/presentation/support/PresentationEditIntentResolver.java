package com.lark.imcollab.harness.presentation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.entity.PresentationEditOperation;
import com.lark.imcollab.common.model.enums.PresentationAnchorMode;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationIterationIntentType;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
                    .pageIndex(optionalInt(root.path("pageIndex")))
                    .insertAfterPageIndex(optionalInt(root.path("insertAfterPageIndex")))
                    .slideTitle(text(root, "slideTitle"))
                    .slideBody(text(root, "slideBody"))
                    .replacementText(text(root, "replacementText"))
                    .targetElementType(parseTargetElementType(root.path("targetElementType").asText(null)))
                    .anchorMode(parseAnchorMode(root.path("anchorMode").asText(null)))
                    .quotedText(text(root, "quotedText"))
                    .elementRole(text(root, "elementRole"))
                    .expectedMatchCount(optionalInt(root.path("expectedMatchCount")))
                    .contentInstruction(text(root, "contentInstruction"))
                    .targetElementId(text(root, "targetElementId"))
                    .targetBlockId(text(root, "targetBlockId"))
                    .operations(parseOperations(root.path("operations")))
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
            List<PresentationEditOperation> operations = intent.getOperations();
            if (operations != null && !operations.isEmpty()) {
                intent.setActionType(operations.get(0).getActionType());
            } else {
                return clarification(intent.getUserInstruction());
            }
        }
        List<PresentationEditOperation> operations = normalizeOperations(intent);
        if (operations.isEmpty()) {
            return clarification(intent.getUserInstruction());
        }
        for (PresentationEditOperation operation : operations) {
            if (operation.getActionType() == null) {
                return clarification(intent.getUserInstruction());
            }
            if (operation.getActionType() == PresentationEditActionType.INSERT_SLIDE
                    && operation.getInsertAfterPageIndex() == null
                    && operation.getPageIndex() != null) {
                operation.setInsertAfterPageIndex(operation.getPageIndex());
            }
            if (requiresPageIndex(operation.getActionType()) && operation.getPageIndex() == null) {
                return clarification(intent.getUserInstruction());
            }
            if (operation.getActionType() == PresentationEditActionType.MOVE_SLIDE
                    && operation.getInsertAfterPageIndex() == null) {
                return clarification(intent.getUserInstruction());
            }
            if (operation.getActionType() == PresentationEditActionType.INSERT_SLIDE
                    && !hasText(operation.getSlideTitle())
                    && !hasText(operation.getSlideBody())
                    && !hasText(operation.getReplacementText())) {
                return clarification(intent.getUserInstruction());
            }
            if (requiresReplacement(operation.getActionType()) && !hasText(operation.getReplacementText())) {
                return clarification(intent.getUserInstruction());
            }
            if (operation.getTargetElementType() == null && operation.getActionType() == PresentationEditActionType.REPLACE_SLIDE_BODY) {
                operation.setTargetElementType(PresentationTargetElementType.BODY);
            }
            if (operation.getTargetElementType() == null && operation.getActionType() == PresentationEditActionType.REPLACE_SLIDE_TITLE) {
                operation.setTargetElementType(PresentationTargetElementType.TITLE);
            }
        }
        intent.setOperations(operations);
        PresentationEditOperation first = operations.get(0);
        intent.setActionType(first.getActionType());
        intent.setPageIndex(first.getPageIndex());
        intent.setInsertAfterPageIndex(first.getInsertAfterPageIndex());
        intent.setSlideTitle(first.getSlideTitle());
        intent.setSlideBody(first.getSlideBody());
        intent.setReplacementText(first.getReplacementText());
        if (intent.getTargetElementType() == null) {
            intent.setTargetElementType(first.getTargetElementType());
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
                  "actionType": "REPLACE_SLIDE_TITLE|REPLACE_SLIDE_BODY|REWRITE_ELEMENT|EXPAND_ELEMENT|SHORTEN_ELEMENT|REPLACE_ELEMENT|REPLACE_IMAGE|REPLACE_CHART|INSERT_SLIDE|DELETE_SLIDE|MOVE_SLIDE",
                  "targetElementType": "TITLE|BODY|IMAGE|CHART|TABLE|CAPTION|SHAPE",
                  "anchorMode": "BY_PAGE_INDEX|BY_QUOTED_TEXT|BY_ELEMENT_ROLE|BY_BLOCK_ID",
                  "pageIndex": 1,
                  "insertAfterPageIndex": 1,
                  "quotedText": "原文引用",
                  "elementRole": "right-image|hero-image|caption|chart",
                  "expectedMatchCount": 1,
                  "contentInstruction": "写详细一些",
                  "targetElementId": "element-1",
                  "targetBlockId": "block-1",
                  "slideTitle": "新增页标题",
                  "slideBody": "新增页正文或要点",
                  "replacementText": "新的标题或内容",
                  "operations": [
                    {
                      "actionType": "REPLACE_SLIDE_TITLE|REPLACE_SLIDE_BODY|REWRITE_ELEMENT|EXPAND_ELEMENT|SHORTEN_ELEMENT|REPLACE_ELEMENT|REPLACE_IMAGE|REPLACE_CHART|INSERT_SLIDE|DELETE_SLIDE|MOVE_SLIDE",
                      "targetElementType": "TITLE|BODY|IMAGE|CHART|TABLE|CAPTION|SHAPE",
                      "anchorMode": "BY_PAGE_INDEX|BY_QUOTED_TEXT|BY_ELEMENT_ROLE|BY_BLOCK_ID",
                      "pageIndex": 1,
                      "insertAfterPageIndex": 1,
                      "quotedText": "原文引用",
                      "elementRole": "right-image|hero-image|caption|chart",
                      "expectedMatchCount": 1,
                      "contentInstruction": "写详细一些",
                      "targetElementId": "element-1",
                      "targetBlockId": "block-1",
                      "slideTitle": "新增页标题",
                      "slideBody": "新增页正文或要点",
                      "replacementText": "新的标题或内容"
                    }
                  ],
                  "clarificationNeeded": false,
                  "clarificationHint": ""
                }

                规则:
                1. 优先输出 operations，支持一条指令包含多个页面修改操作。
                2. 替换标题或正文时，只有在每个操作都能明确识别页码、目标元素和新内容时，clarificationNeeded=false。
                3. “把第一页标题改成7878”“第一页标题为7878”“修改第3页标题为实施收益”都应解析为 REPLACE_SLIDE_TITLE。
                4. 如果用户要求修改正文、要点、内容，targetElementType 应为 BODY。
                5. 如果用户说“第一页这段 xxx 写详细一些”，优先使用 targetElementType=BODY、anchorMode=BY_QUOTED_TEXT、quotedText=xxx、actionType=EXPAND_ELEMENT。
                6. 如果用户说“第2页右侧图片换成门店实景图”，使用 targetElementType=IMAGE、anchorMode=BY_ELEMENT_ROLE、elementRole=right-image、actionType=REPLACE_IMAGE。
                7. 如果用户说“第3页流程图改成采购->评审->执行”，使用 targetElementType=CHART、actionType=REPLACE_CHART。
                5. “在第2页后插入一页，标题为风险应对，正文为预算、排期、依赖”应解析为 INSERT_SLIDE，insertAfterPageIndex=2，slideTitle/slideBody 填入新增内容；插到最前 insertAfterPageIndex=0；插到末尾 insertAfterPageIndex 可为 null。
                6. “删除第3页”应解析为 DELETE_SLIDE，pageIndex=3。
                7. “把第4页移到第2页后”应解析为 MOVE_SLIDE，pageIndex=4，insertAfterPageIndex=2；移到最前 insertAfterPageIndex=0；移到最后/末尾 insertAfterPageIndex=-1。
                8. 无法唯一定位时必须 clarificationNeeded=true，禁止默认猜标题。
                9. 新增页缺少标题和正文，或移动页缺少源页/目标位置，或无法确认替换页码、目标元素、新内容时，clarificationNeeded=true。

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

    private PresentationTargetElementType parseTargetElementType(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return PresentationTargetElementType.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private PresentationAnchorMode parseAnchorMode(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return PresentationAnchorMode.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<PresentationEditOperation> parseOperations(JsonNode root) {
        List<PresentationEditOperation> operations = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return operations;
        }
        for (JsonNode node : root) {
            PresentationEditOperation operation = PresentationEditOperation.builder()
                    .actionType(parseActionType(node.path("actionType").asText(null)))
                    .targetElementType(parseTargetElementType(node.path("targetElementType").asText(null)))
                    .pageIndex(optionalInt(node.path("pageIndex")))
                    .insertAfterPageIndex(optionalInt(node.path("insertAfterPageIndex")))
                    .slideTitle(text(node, "slideTitle"))
                    .slideBody(text(node, "slideBody"))
                    .replacementText(text(node, "replacementText"))
                    .anchorMode(parseAnchorMode(node.path("anchorMode").asText(null)))
                    .quotedText(text(node, "quotedText"))
                    .elementRole(text(node, "elementRole"))
                    .expectedMatchCount(optionalInt(node.path("expectedMatchCount")))
                    .contentInstruction(text(node, "contentInstruction"))
                    .targetElementId(text(node, "targetElementId"))
                    .targetBlockId(text(node, "targetBlockId"))
                    .build();
            operations.add(operation);
        }
        return operations;
    }

    private List<PresentationEditOperation> normalizeOperations(PresentationEditIntent intent) {
        List<PresentationEditOperation> operations = intent.getOperations();
        if (operations != null && !operations.isEmpty()) {
            return operations;
        }
        if (intent.getActionType() == null && intent.getPageIndex() == null && !hasText(intent.getReplacementText())) {
            return List.of();
        }
        PresentationEditOperation operation = PresentationEditOperation.builder()
                .actionType(intent.getActionType())
                .targetElementType(intent.getTargetElementType())
                .pageIndex(intent.getPageIndex())
                .insertAfterPageIndex(intent.getInsertAfterPageIndex())
                .slideTitle(intent.getSlideTitle())
                .slideBody(intent.getSlideBody())
                .replacementText(intent.getReplacementText())
                .anchorMode(intent.getAnchorMode())
                .quotedText(intent.getQuotedText())
                .elementRole(intent.getElementRole())
                .expectedMatchCount(intent.getExpectedMatchCount())
                .contentInstruction(intent.getContentInstruction())
                .targetElementId(intent.getTargetElementId())
                .targetBlockId(intent.getTargetBlockId())
                .build();
        return new ArrayList<>(List.of(operation));
    }

    private boolean requiresReplacement(PresentationEditActionType actionType) {
        return actionType == PresentationEditActionType.REPLACE_SLIDE_TITLE
                || actionType == PresentationEditActionType.REPLACE_SLIDE_BODY
                || actionType == PresentationEditActionType.REWRITE_ELEMENT
                || actionType == PresentationEditActionType.EXPAND_ELEMENT
                || actionType == PresentationEditActionType.SHORTEN_ELEMENT
                || actionType == PresentationEditActionType.REPLACE_ELEMENT
                || actionType == PresentationEditActionType.REPLACE_IMAGE
                || actionType == PresentationEditActionType.REPLACE_CHART;
    }

    private boolean requiresPageIndex(PresentationEditActionType actionType) {
        return actionType == PresentationEditActionType.REPLACE_SLIDE_TITLE
                || actionType == PresentationEditActionType.REPLACE_SLIDE_BODY
                || actionType == PresentationEditActionType.DELETE_SLIDE
                || actionType == PresentationEditActionType.MOVE_SLIDE;
    }

    private Integer optionalInt(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isTextual() && hasText(node.asText())) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String text(JsonNode root, String field) {
        String value = root.path(field).asText(null);
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
