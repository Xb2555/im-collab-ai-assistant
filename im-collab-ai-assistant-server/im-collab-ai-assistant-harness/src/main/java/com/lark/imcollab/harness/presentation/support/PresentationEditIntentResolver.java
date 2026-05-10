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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PresentationEditIntentResolver implements PresentationEditIntentFacade {
    private static final Pattern PAGE_PREFIX_PATTERN = Pattern.compile(
            "^(?:(?:第\\s*(?<cn>[一二三四五六七八九十两\\d]+)\\s*页)|(?:(?<named>封面页|目录页|首页|最后一页|末页|尾页)))");
    private static final Pattern EXPAND_ANCHOR_PATTERN = Pattern.compile(
            "(?<anchor>.+?)(?:这一段|这段|这一部分|这部分)(?<action>写得|写的|展开|补充|丰富)?(?<degree>详细一些|更详细一些|详细点|展开一些|补充一些|丰富一些)?$");
    private static final Pattern SHORTEN_ANCHOR_PATTERN = Pattern.compile(
            "(?<anchor>.+?)(?:这一段|这段|这一部分|这部分)(?<action>精简|缩短|压缩|简化)(?<degree>一些|一点)?$");
    private static final Pattern DELETE_ANCHOR_PATTERN = Pattern.compile(
            "(?<anchor>.+?)(?:这一段|这段|这一部分|这部分)(?<action>删了|删掉|删除|去掉|移除)$");
    private static final Pattern INSERT_AFTER_ANCHOR_PATTERN = Pattern.compile(
            "(?:在)?(?<anchor>.+?)(?:后|后面)(?<action>插入|补充|加上|增加)(?<degree>新的一小点|一小点|一点|一句|一条|一段|内容.*)?$");

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
                    .targetPageTitle(text(root, "targetPageTitle"))
                    .targetParagraphIndex(optionalInt(root.path("targetParagraphIndex")))
                    .targetListItemIndex(optionalInt(root.path("targetListItemIndex")))
                    .targetNodePath(text(root, "targetNodePath"))
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
            return heuristicFallback(null);
        }
        if (intent.isClarificationNeeded()) {
            PresentationEditIntent fallback = heuristicFallback(intent.getUserInstruction());
            if (fallback.getOperations() != null && !fallback.getOperations().isEmpty()) {
                return ensureHint(fallback);
            }
            return fallback.isClarificationNeeded() ? ensureHint(intent) : fallback;
        }
        if (intent.getIntentType() == null || intent.getActionType() == null) {
            List<PresentationEditOperation> operations = intent.getOperations();
            if (operations != null && !operations.isEmpty()) {
                intent.setActionType(operations.get(0).getActionType());
            } else {
                return heuristicFallback(intent.getUserInstruction());
            }
        }
        List<PresentationEditOperation> operations = normalizeOperations(intent);
        if (operations.isEmpty()) {
            return heuristicFallback(intent.getUserInstruction());
        }
        coerceOperationsByInstruction(intent.getUserInstruction(), operations);
        for (PresentationEditOperation operation : operations) {
            if (operation.getActionType() == null) {
                return heuristicFallback(intent.getUserInstruction());
            }
            if (operation.getActionType() == PresentationEditActionType.INSERT_SLIDE
                    && operation.getInsertAfterPageIndex() == null
                    && operation.getPageIndex() != null) {
                operation.setInsertAfterPageIndex(operation.getPageIndex());
            }
            if (requiresPageIndex(operation.getActionType()) && operation.getPageIndex() == null) {
                return heuristicFallback(intent.getUserInstruction());
            }
            if (operation.getActionType() == PresentationEditActionType.MOVE_SLIDE
                    && operation.getInsertAfterPageIndex() == null) {
                return heuristicFallback(intent.getUserInstruction());
            }
            if (operation.getActionType() == PresentationEditActionType.INSERT_SLIDE
                    && !hasText(operation.getSlideTitle())
                    && !hasText(operation.getSlideBody())
                    && !hasText(operation.getReplacementText())) {
                return heuristicFallback(intent.getUserInstruction());
            }
            if (requiresReplacement(operation.getActionType()) && !hasText(operation.getReplacementText())) {
                return heuristicFallback(intent.getUserInstruction());
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

    private void coerceOperationsByInstruction(String instruction, List<PresentationEditOperation> operations) {
        if (!hasText(instruction) || operations == null || operations.isEmpty()) {
            return;
        }
        String normalized = instruction.trim();
        for (PresentationEditOperation operation : operations) {
            if (operation == null) {
                continue;
            }
            if (containsDeleteSemantic(normalized)) {
                coerceDeleteOperation(operation, normalized);
                continue;
            }
            if (containsInsertSemantic(normalized) && operation.getActionType() == PresentationEditActionType.REWRITE_ELEMENT) {
                operation.setActionType(PresentationEditActionType.INSERT_AFTER_ELEMENT);
                if (operation.getTargetElementType() == null) {
                    operation.setTargetElementType(PresentationTargetElementType.BODY);
                }
                if (operation.getAnchorMode() == null && hasText(operation.getQuotedText())) {
                    operation.setAnchorMode(PresentationAnchorMode.BY_QUOTED_TEXT);
                }
            }
        }
    }

    private void coerceDeleteOperation(PresentationEditOperation operation, String instruction) {
        if (operation.getActionType() == PresentationEditActionType.DELETE_SLIDE
                || operation.getActionType() == PresentationEditActionType.DELETE_ELEMENT) {
            return;
        }
        if (mentionsSlideDelete(instruction) && operation.getPageIndex() != null) {
            operation.setActionType(PresentationEditActionType.DELETE_SLIDE);
            return;
        }
        operation.setActionType(PresentationEditActionType.DELETE_ELEMENT);
        if (operation.getTargetElementType() == null) {
            operation.setTargetElementType(PresentationTargetElementType.BODY);
        }
        if (operation.getAnchorMode() == null && hasText(operation.getQuotedText())) {
            operation.setAnchorMode(PresentationAnchorMode.BY_QUOTED_TEXT);
        }
        if (operation.getExpectedMatchCount() == null) {
            operation.setExpectedMatchCount(1);
        }
    }

    private PresentationEditIntent clarification(String instruction) {
        return PresentationEditIntent.builder()
                .userInstruction(instruction)
                .clarificationNeeded(true)
                .clarificationHint("请明确要改第几页、修改哪个元素，以及改成什么内容。")
                .build();
    }

    private PresentationEditIntent heuristicFallback(String instruction) {
        PresentationEditIntent inserted = anchorInsertFallback(instruction);
        if (inserted.getOperations() != null && !inserted.getOperations().isEmpty()) {
            return inserted;
        }
        PresentationEditIntent expanded = anchorRewriteFallback(instruction, EXPAND_ANCHOR_PATTERN, PresentationEditActionType.EXPAND_ELEMENT);
        if (expanded.getOperations() != null && !expanded.getOperations().isEmpty()) {
            return expanded;
        }
        PresentationEditIntent shortened = anchorRewriteFallback(instruction, SHORTEN_ANCHOR_PATTERN, PresentationEditActionType.SHORTEN_ELEMENT);
        if (shortened.getOperations() != null && !shortened.getOperations().isEmpty()) {
            return shortened;
        }
        PresentationEditIntent deleted = anchorRewriteFallback(instruction, DELETE_ANCHOR_PATTERN, PresentationEditActionType.DELETE_ELEMENT);
        if (deleted.getOperations() != null && !deleted.getOperations().isEmpty()) {
            return deleted;
        }
        return clarification(instruction);
    }

    private PresentationEditIntent anchorInsertFallback(String instruction) {
        if (!hasText(instruction)) {
            return clarification(instruction);
        }
        Matcher matcher = INSERT_AFTER_ANCHOR_PATTERN.matcher(instruction.trim());
        if (!matcher.find()) {
            return clarification(instruction);
        }
        String anchor = cleanAnchor(matcher.group("anchor"));
        if (!hasText(anchor)) {
            return clarification(instruction);
        }
        PageAnchor pageAnchor = extractPageAnchor(anchor);
        PresentationEditOperation operation = PresentationEditOperation.builder()
                .actionType(PresentationEditActionType.INSERT_AFTER_ELEMENT)
                .targetElementType(PresentationTargetElementType.BODY)
                .anchorMode(PresentationAnchorMode.BY_QUOTED_TEXT)
                .pageIndex(pageAnchor.pageIndex())
                .quotedText(pageAnchor.anchorText())
                .contentInstruction(instruction.trim())
                .expectedMatchCount(1)
                .targetParagraphIndex(inferParagraphIndex(instruction))
                .targetListItemIndex(inferListItemIndex(instruction))
                .build();
        return PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.UPDATE_CONTENT)
                .actionType(PresentationEditActionType.INSERT_AFTER_ELEMENT)
                .userInstruction(instruction)
                .targetElementType(PresentationTargetElementType.BODY)
                .anchorMode(PresentationAnchorMode.BY_QUOTED_TEXT)
                .pageIndex(pageAnchor.pageIndex())
                .quotedText(pageAnchor.anchorText())
                .contentInstruction(instruction.trim())
                .operations(List.of(operation))
                .clarificationNeeded(false)
                .build();
    }

    private PresentationEditIntent anchorRewriteFallback(
            String instruction,
            Pattern pattern,
            PresentationEditActionType actionType
    ) {
        if (!hasText(instruction) || pattern == null || actionType == null) {
            return clarification(instruction);
        }
        Matcher matcher = pattern.matcher(instruction.trim());
        if (!matcher.find()) {
            return clarification(instruction);
        }
        String anchor = cleanAnchor(matcher.group("anchor"));
        if (!hasText(anchor)) {
            return clarification(instruction);
        }
        PageAnchor pageAnchor = extractPageAnchor(anchor);
        PresentationEditOperation operation = PresentationEditOperation.builder()
                .actionType(actionType)
                .targetElementType(PresentationTargetElementType.BODY)
                .anchorMode(PresentationAnchorMode.BY_QUOTED_TEXT)
                .pageIndex(pageAnchor.pageIndex())
                .quotedText(pageAnchor.anchorText())
                .contentInstruction(instruction.trim())
                .expectedMatchCount(1)
                .targetParagraphIndex(inferParagraphIndex(instruction))
                .targetListItemIndex(inferListItemIndex(instruction))
                .build();
        return PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.UPDATE_CONTENT)
                .actionType(actionType)
                .userInstruction(instruction)
                .targetElementType(PresentationTargetElementType.BODY)
                .anchorMode(PresentationAnchorMode.BY_QUOTED_TEXT)
                .pageIndex(pageAnchor.pageIndex())
                .quotedText(pageAnchor.anchorText())
                .contentInstruction(instruction.trim())
                .operations(List.of(operation))
                .clarificationNeeded(false)
                .build();
    }

    private String cleanAnchor(String anchor) {
        if (!hasText(anchor)) {
            return null;
        }
        String normalized = anchor.trim()
                .replaceAll("^(把|将|把这|将这)", "")
                .replaceAll("(给我|帮我|麻烦你)$", "")
                .replaceAll("^[“”\"'‘’]+", "")
                .replaceAll("[“”\"'‘’]+$", "")
                .trim();
        return hasText(normalized) ? normalized : null;
    }

    private PageAnchor extractPageAnchor(String anchor) {
        if (!hasText(anchor)) {
            return new PageAnchor(null, null);
        }
        Matcher matcher = PAGE_PREFIX_PATTERN.matcher(anchor.trim());
        if (!matcher.find()) {
            return new PageAnchor(null, anchor.trim());
        }
        Integer pageIndex = parsePageIndex(matcher.group("cn"), matcher.group("named"));
        String remainder = anchor.trim().substring(matcher.end()).trim();
        remainder = remainder.replaceFirst("^(的|上|里|中)", "").trim();
        return new PageAnchor(pageIndex, hasText(remainder) ? remainder : anchor.trim());
    }

    private Integer parsePageIndex(String pageToken, String namedToken) {
        if (hasText(namedToken)) {
            return switch (namedToken.trim()) {
                case "封面页", "首页" -> 1;
                default -> null;
            };
        }
        if (!hasText(pageToken)) {
            return null;
        }
        String normalized = pageToken.trim();
        if (normalized.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(normalized);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return chineseNumberToInt(normalized);
    }

    private Integer chineseNumberToInt(String value) {
        if (!hasText(value)) {
            return null;
        }
        return switch (value.trim()) {
            case "一" -> 1;
            case "二", "两" -> 2;
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
                  "actionType": "REPLACE_SLIDE_TITLE|REPLACE_SLIDE_BODY|REWRITE_ELEMENT|EXPAND_ELEMENT|SHORTEN_ELEMENT|REPLACE_ELEMENT|REPLACE_IMAGE|REPLACE_CHART|INSERT_AFTER_ELEMENT|DELETE_ELEMENT|INSERT_SLIDE|DELETE_SLIDE|MOVE_SLIDE",
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
                  "targetPageTitle": "实施路径",
                  "targetParagraphIndex": 2,
                  "targetListItemIndex": 3,
                  "targetNodePath": "slide/data/shape[2]/content[1]/p[2]",
                  "slideTitle": "新增页标题",
                  "slideBody": "新增页正文或要点",
                  "replacementText": "新的标题或内容",
                  "operations": [
                    {
                      "actionType": "REPLACE_SLIDE_TITLE|REPLACE_SLIDE_BODY|REWRITE_ELEMENT|EXPAND_ELEMENT|SHORTEN_ELEMENT|REPLACE_ELEMENT|REPLACE_IMAGE|REPLACE_CHART|INSERT_AFTER_ELEMENT|DELETE_ELEMENT|INSERT_SLIDE|DELETE_SLIDE|MOVE_SLIDE",
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
                      "targetPageTitle": "实施路径",
                      "targetParagraphIndex": 2,
                      "targetListItemIndex": 3,
                      "targetNodePath": "slide/data/shape[2]/content[1]/p[2]",
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
                7.1 如果用户明确说“第2页第三个 bullet”“第二段”“第1页第2条”，优先填 targetListItemIndex 或 targetParagraphIndex。
                7.2 如果用户说“第一页的 xxx 这一段删了/删除/去掉”，必须使用 targetElementType=BODY、anchorMode=BY_QUOTED_TEXT、quotedText=xxx、actionType=DELETE_ELEMENT，禁止返回 REWRITE_ELEMENT。
                7.3 如果用户说“在第一页的 xxx 后插入一句/一段”，优先使用 targetElementType=BODY、anchorMode=BY_QUOTED_TEXT、quotedText=xxx、actionType=INSERT_AFTER_ELEMENT。
                8. “在第2页后插入一页，标题为风险应对，正文为预算、排期、依赖”应解析为 INSERT_SLIDE，insertAfterPageIndex=2，slideTitle/slideBody 填入新增内容；插到最前 insertAfterPageIndex=0；插到末尾 insertAfterPageIndex 可为 null。
                9. “删除第3页”应解析为 DELETE_SLIDE，pageIndex=3。
                10. “把第4页移到第2页后”应解析为 MOVE_SLIDE，pageIndex=4，insertAfterPageIndex=2；移到最前 insertAfterPageIndex=0；移到最后/末尾 insertAfterPageIndex=-1。
                11. 无法唯一定位时必须 clarificationNeeded=true，禁止默认猜标题。
                12. 新增页缺少标题和正文，或移动页缺少源页/目标位置，或无法确认替换页码、目标元素、新内容时，clarificationNeeded=true。

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
                    .targetPageTitle(text(node, "targetPageTitle"))
                    .targetParagraphIndex(optionalInt(node.path("targetParagraphIndex")))
                    .targetListItemIndex(optionalInt(node.path("targetListItemIndex")))
                    .targetNodePath(text(node, "targetNodePath"))
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
                .targetPageTitle(intent.getTargetPageTitle())
                .targetParagraphIndex(intent.getTargetParagraphIndex())
                .targetListItemIndex(intent.getTargetListItemIndex())
                .targetNodePath(intent.getTargetNodePath())
                .build();
        return new ArrayList<>(List.of(operation));
    }

    private Integer inferParagraphIndex(String instruction) {
        if (!hasText(instruction)) {
            return null;
        }
        Matcher matcher = Pattern.compile("第\\s*(\\d+)\\s*段").matcher(instruction);
        if (matcher.find()) {
            return parseNumericToken(matcher.group(1));
        }
        return null;
    }

    private Integer inferListItemIndex(String instruction) {
        if (!hasText(instruction)) {
            return null;
        }
        Matcher matcher = Pattern.compile("第\\s*(\\d+)\\s*(?:个|条)?\\s*(?:bullet|要点|点)").matcher(instruction.toLowerCase());
        if (matcher.find()) {
            return parseNumericToken(matcher.group(1));
        }
        return null;
    }

    private Integer parseNumericToken(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean requiresReplacement(PresentationEditActionType actionType) {
        return actionType == PresentationEditActionType.REPLACE_SLIDE_TITLE
                || actionType == PresentationEditActionType.REPLACE_SLIDE_BODY
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

    private boolean containsDeleteSemantic(String instruction) {
        return instruction.contains("删了")
                || instruction.contains("删掉")
                || instruction.contains("删除")
                || instruction.contains("去掉")
                || instruction.contains("移除");
    }

    private boolean containsInsertSemantic(String instruction) {
        return instruction.contains("插入")
                || instruction.contains("补充")
                || instruction.contains("加上")
                || instruction.contains("增加");
    }

    private boolean mentionsSlideDelete(String instruction) {
        return instruction.contains("删除第")
                || instruction.contains("删掉第")
                || instruction.contains("删第")
                || instruction.contains("移除第");
    }

    private record PageAnchor(Integer pageIndex, String anchorText) {
    }
}
