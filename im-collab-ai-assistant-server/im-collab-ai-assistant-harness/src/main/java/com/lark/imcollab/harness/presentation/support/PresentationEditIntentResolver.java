package com.lark.imcollab.harness.presentation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.entity.PresentationEditOperation;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PresentationAnchorMode;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationIterationIntentType;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PresentationEditIntentResolver implements PresentationEditIntentFacade {

    private static final Pattern PAGE_TITLE_REPLACE_PATTERN = Pattern.compile("第?([一二三四五六七八九十百千万0-9]+)页[^。；，,\n]*?标题(?:改成|改为|为)([^。；，,\n]+)");
    private static final Pattern PAGE_BODY_REPLACE_PATTERN = Pattern.compile("第?([一二三四五六七八九十百千万0-9]+)页[^。；，,\n]*?(?:正文|内容|要点)(?:改成|改为)([^。；，,\n]+)");
    private static final Pattern PAGE_GENERIC_REPLACE_PATTERN = Pattern.compile("第?([一二三四五六七八九十百千万0-9]+)页[^。；，,\n]*?改成([^。；，,\n]+)");
    private static final Pattern APPEND_SLIDE_AT_END_PATTERN = Pattern.compile("(?:在)?(?:末尾|最后)(?:补|新增|加)(?:一页)?([^。；，,\n]+)");
    private static final Pattern LAST_PAGE_BODY_APPEND_PATTERN = Pattern.compile("(?:给|把)?最后一页(?:加一段|补一段|增加一段)([^。；，,\n]+)");

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public PresentationEditIntentResolver(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public PresentationEditIntent resolve(String instruction) {
        return resolve(instruction, null);
    }

    @Override
    public PresentationEditIntent resolve(String instruction, WorkspaceContext workspaceContext) {
        try {
            String response = chatModel.call(buildPrompt(instruction, workspaceContext));
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
        intent = applyNaturalLanguageHeuristics(intent);
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

    private PresentationEditIntent applyNaturalLanguageHeuristics(PresentationEditIntent intent) {
        if (intent == null || !hasText(intent.getUserInstruction())) {
            return intent;
        }
        String instruction = intent.getUserInstruction();
        List<PresentationEditOperation> heuristicOperations = inferHeuristicOperations(instruction);
        if (heuristicOperations.isEmpty()) {
            return intent;
        }
        intent.setClarificationNeeded(false);
        intent.setClarificationHint(null);
        intent.setIntentType(resolveIntentTypeFromOperation(heuristicOperations.get(0).getActionType()));
        intent.setOperations(heuristicOperations);
        return intent;
    }

    private List<PresentationEditOperation> inferHeuristicOperations(String instruction) {
        List<PresentationEditOperation> operations = inferTitleReplacementOperations(instruction);
        if (!operations.isEmpty()) {
            return operations;
        }
        PresentationEditOperation appendSlide = inferAppendSlideOperation(instruction);
        if (appendSlide != null) {
            return List.of(appendSlide);
        }
        PresentationEditOperation appendLastPageBody = inferLastPageBodyAppendOperation(instruction);
        if (appendLastPageBody != null) {
            return List.of(appendLastPageBody);
        }
        return List.of();
    }

    private List<PresentationEditOperation> inferTitleReplacementOperations(String instruction) {
        List<PresentationEditOperation> operations = new ArrayList<>();
        for (String clause : instruction.split("[，,；;]")) {
            String trimmedClause = clause == null ? "" : clause.trim();
            if (!hasText(trimmedClause)) {
                continue;
            }
            Matcher explicitTitleMatcher = PAGE_TITLE_REPLACE_PATTERN.matcher(trimmedClause);
            if (explicitTitleMatcher.find()) {
                Integer pageIndex = parsePageIndex(explicitTitleMatcher.group(1));
                String replacement = trimHeuristicText(explicitTitleMatcher.group(2));
                if (pageIndex != null && hasText(replacement)) {
                    operations.add(titleReplaceOperation(pageIndex, replacement));
                }
                continue;
            }
            Matcher explicitBodyMatcher = PAGE_BODY_REPLACE_PATTERN.matcher(trimmedClause);
            if (explicitBodyMatcher.find()) {
                Integer pageIndex = parsePageIndex(explicitBodyMatcher.group(1));
                String replacement = trimHeuristicText(explicitBodyMatcher.group(2));
                if (pageIndex != null && hasText(replacement)) {
                    operations.add(PresentationEditOperation.builder()
                            .actionType(PresentationEditActionType.REPLACE_SLIDE_BODY)
                            .targetElementType(PresentationTargetElementType.BODY)
                            .pageIndex(pageIndex)
                            .replacementText(replacement)
                            .build());
                }
                continue;
            }
            Matcher genericMatcher = PAGE_GENERIC_REPLACE_PATTERN.matcher(trimmedClause);
            if (genericMatcher.find()) {
                Integer pageIndex = parsePageIndex(genericMatcher.group(1));
                String replacement = trimHeuristicText(genericMatcher.group(2));
                if (pageIndex != null && hasText(replacement) && !looksLikeBodyReplacement(trimmedClause)) {
                    operations.add(titleReplaceOperation(pageIndex, replacement));
                }
            }
        }
        return operations;
    }

    private PresentationEditOperation inferAppendSlideOperation(String instruction) {
        Matcher matcher = APPEND_SLIDE_AT_END_PATTERN.matcher(instruction);
        if (!matcher.find()) {
            return null;
        }
        String title = trimHeuristicText(matcher.group(1));
        if (!hasText(title)) {
            return null;
        }
        return PresentationEditOperation.builder()
                .actionType(PresentationEditActionType.INSERT_SLIDE)
                .insertAfterPageIndex(null)
                .slideTitle(title)
                .targetElementType(PresentationTargetElementType.TITLE)
                .build();
    }

    private PresentationEditOperation inferLastPageBodyAppendOperation(String instruction) {
        Matcher matcher = LAST_PAGE_BODY_APPEND_PATTERN.matcher(instruction);
        if (!matcher.find()) {
            return null;
        }
        String replacement = trimHeuristicText(matcher.group(1));
        if (!hasText(replacement)) {
            return null;
        }
        return PresentationEditOperation.builder()
                .actionType(PresentationEditActionType.REPLACE_SLIDE_BODY)
                .pageIndex(-1)
                .targetElementType(PresentationTargetElementType.BODY)
                .replacementText(replacement)
                .build();
    }

    private PresentationIterationIntentType resolveIntentTypeFromOperation(PresentationEditActionType actionType) {
        if (actionType == PresentationEditActionType.INSERT_SLIDE) {
            return PresentationIterationIntentType.INSERT;
        }
        if (actionType == PresentationEditActionType.DELETE_SLIDE) {
            return PresentationIterationIntentType.DELETE;
        }
        return PresentationIterationIntentType.UPDATE_CONTENT;
    }

    private PresentationEditOperation titleReplaceOperation(Integer pageIndex, String replacement) {
        return PresentationEditOperation.builder()
                .actionType(PresentationEditActionType.REPLACE_SLIDE_TITLE)
                .targetElementType(PresentationTargetElementType.TITLE)
                .pageIndex(pageIndex)
                .replacementText(replacement)
                .build();
    }

    private boolean looksLikeBodyReplacement(String instruction) {
        if (!hasText(instruction)) {
            return false;
        }
        String lower = instruction.toLowerCase(Locale.ROOT);
        return lower.contains("正文")
                || lower.contains("内容")
                || lower.contains("要点")
                || lower.contains("加一段")
                || lower.contains("补一段");
    }

    private Integer parsePageIndex(String rawValue) {
        if (!hasText(rawValue)) {
            return null;
        }
        String normalized = rawValue.trim();
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            return switch (normalized) {
                case "一" -> 1;
                case "二" -> 2;
                case "三" -> 3;
                case "四" -> 4;
                case "五" -> 5;
                case "六" -> 6;
                case "七" -> 7;
                case "八" -> 8;
                case "九" -> 9;
                case "十" -> 10;
                default -> null;
            };
        }
    }

    private String trimHeuristicText(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim()
                .replaceFirst("^(标题为|标题改成|标题改为)", "")
                .trim();
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

    private String buildPrompt(String instruction, WorkspaceContext workspaceContext) {
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
                10. 如果用户要求“把最后一页改成最近10分钟关于风险的消息总结”“基于最近聊天记录补一页总结”，应优先理解为修改最后一页正文，而不是要求再次澄清。

                已有上下文素材：
                %s

                用户指令：%s
                """.formatted(summarizeWorkspaceContext(workspaceContext), instruction == null ? "" : instruction.trim());
    }

    private String summarizeWorkspaceContext(WorkspaceContext workspaceContext) {
        if (workspaceContext == null) {
            return "无";
        }
        List<String> parts = new ArrayList<>();
        if (hasText(workspaceContext.getTimeRange())) {
            parts.add("timeRange=" + workspaceContext.getTimeRange());
        }
        if (workspaceContext.getSelectedMessages() != null && !workspaceContext.getSelectedMessages().isEmpty()) {
            parts.add("selectedMessages=" + workspaceContext.getSelectedMessages().stream()
                    .filter(this::hasText)
                    .limit(6)
                    .reduce((left, right) -> left + " | " + right)
                    .orElse(""));
        }
        return parts.isEmpty() ? "无" : String.join("\n", parts);
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
