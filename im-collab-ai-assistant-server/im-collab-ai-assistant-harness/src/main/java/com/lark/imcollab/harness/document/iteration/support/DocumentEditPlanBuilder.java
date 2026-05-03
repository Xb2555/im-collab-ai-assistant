package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.common.model.entity.DocumentTargetSelector;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentLocatorStrategy;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.common.model.enums.DocumentRelativePosition;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class DocumentEditPlanBuilder {

    private static final Pattern STRUCTURED_HEADING_PATTERN = Pattern.compile(
            "^(?:[一二三四五六七八九十百千万0-9]+[、.．]|[0-9]+(?:\\.[0-9]+)+)\\s*\\S+.*$"
    );

    private enum DeleteScope {
        BODY_ONLY,
        WHOLE_SECTION
    }

    private final ChatModel chatModel;

    public DocumentEditPlanBuilder(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public DocumentEditPlan build(
            String taskId,
            DocumentIterationIntentType intentType,
            DocumentTargetSelector selector,
            String instruction
    ) {
        return switch (intentType) {
            case EXPLAIN -> DocumentEditPlan.builder()
                    .taskId(taskId)
                    .intentType(intentType)
                    .selector(selector)
                    .reasoningSummary("已定位目标片段，走只读 explain 链路")
                    .generatedContent(explain(selector.getMatchedExcerpt(), instruction))
                    .toolCommandType(null)
                    .requiresApproval(false)
                    .riskLevel(DocumentRiskLevel.LOW)
                    .patchOperations(List.of())
                    .build();
            case INSERT -> buildInsertPlan(taskId, selector, instruction);
            case UPDATE_CONTENT -> buildRewritePlan(taskId, intentType, selector, instruction, false);
            case UPDATE_STYLE -> buildRewritePlan(taskId, intentType, selector, instruction, true);
            case DELETE -> buildDeletePlan(taskId, selector, instruction);
            case INSERT_MEDIA, ADJUST_LAYOUT -> DocumentEditPlan.builder()
                    .taskId(taskId)
                    .intentType(intentType)
                    .selector(selector)
                    .reasoningSummary("已识别为富媒体或布局调整意图，当前链路仅生成受控计划，不直接执行")
                    .generatedContent("")
                    .toolCommandType(null)
                    .requiresApproval(true)
                    .riskLevel(DocumentRiskLevel.HIGH)
                    .patchOperations(List.of())
                    .build();
        };
    }

    private DocumentEditPlan buildInsertPlan(
            String taskId,
            DocumentTargetSelector selector,
            String instruction
    ) {
        String generated = normalizeInsertedHeadingLevel(insertAfter(selector.getMatchedExcerpt(), instruction), selector.getMatchedExcerpt());
        if (generated.isBlank()) {
            throw new IllegalStateException("新增内容为空，拒绝生成插入计划");
        }
        if (looksLikeHeadingOnly(generated)) {
            throw new IllegalStateException("新增章节只生成了标题、没有正文，拒绝直接写入文档");
        }
        List<String> blockIds = selector.getMatchedBlockIds() == null ? List.of() : selector.getMatchedBlockIds();
        String anchorBlockId = blockIds.isEmpty() ? null : blockIds.get(blockIds.size() - 1);
        DocumentRelativePosition relativePosition = selector.getRelativePosition();
        List<DocumentPatchOperation> operations = new ArrayList<>();
        DocumentPatchOperationType commandType;
        boolean requiresApproval;
        if (selector.getLocatorStrategy() == DocumentLocatorStrategy.DOC_END) {
            commandType = DocumentPatchOperationType.APPEND;
            requiresApproval = false;
            operations.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.APPEND)
                    .newContent(generated)
                    .docFormat("markdown")
                    .justification("在文档末尾新增内容")
                    .build());
        } else if (relativePosition == DocumentRelativePosition.BEFORE) {
            String anchorExcerpt = trim(selector.getMatchedExcerpt());
            if (anchorBlockId != null && !anchorBlockId.isBlank() && !anchorExcerpt.isBlank()) {
                String replacementContent = buildBlockReplacePrependContent(generated, anchorExcerpt);
                commandType = DocumentPatchOperationType.BLOCK_REPLACE;
                requiresApproval = replacementContent.isBlank();
                operations.add(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_REPLACE)
                        .blockId(anchorBlockId)
                        .oldText(anchorExcerpt)
                        .newContent(replacementContent)
                        .docFormat("markdown")
                        .justification("用 block_replace 重写锚点 block，在目标标题前插入新章节并保留原始标题结构")
                        .build());
            } else {
                String anchorTitle = firstHeadingText(selector.getMatchedExcerpt(), selector.getLocatorValue());
                String inlineReplaceContent = buildInlineHeadingPrependContent(generated, selector.getMatchedExcerpt(), anchorTitle);
                commandType = DocumentPatchOperationType.STR_REPLACE;
                requiresApproval = anchorTitle == null || anchorTitle.isBlank() || inlineReplaceContent.isBlank();
                operations.add(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.STR_REPLACE)
                        .oldText(anchorTitle)
                        .newContent(inlineReplaceContent)
                        .docFormat("markdown")
                        .justification("缺少稳定 block 锚点，退回标题文本替换兜底")
                        .build());
            }
        } else {
            commandType = DocumentPatchOperationType.BLOCK_INSERT_AFTER;
            requiresApproval = anchorBlockId == null;
            operations.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                    .blockId(anchorBlockId)
                    .newContent(generated)
                    .docFormat("markdown")
                    .justification("在目标锚点后新增内容")
                    .build());
        }
        return DocumentEditPlan.builder()
                .taskId(taskId)
                .intentType(DocumentIterationIntentType.INSERT)
                .selector(selector)
                .reasoningSummary("基于定位到的锚点新增内容")
                .generatedContent(generated)
                .toolCommandType(commandType)
                .requiresApproval(requiresApproval)
                .riskLevel(DocumentRiskLevel.MEDIUM)
                .patchOperations(operations)
                .build();
    }

    private DocumentEditPlan buildRewritePlan(
            String taskId,
            DocumentIterationIntentType intentType,
            DocumentTargetSelector selector,
            String instruction,
            boolean styleOnly
    ) {
        String generated = rewrite(selector, instruction, styleOnly);
        if (generated.isBlank()) {
            throw new IllegalStateException("改写结果为空，拒绝生成文档修改计划");
        }
        List<String> blockIds = selector.getMatchedBlockIds() == null ? List.of() : selector.getMatchedBlockIds();
        boolean exactBlock = selector.getLocatorStrategy() == DocumentLocatorStrategy.BY_EXACT_TEXT && blockIds.size() == 1;
        boolean sectionBlocks = selector.getLocatorStrategy() == DocumentLocatorStrategy.BY_HEADING && !blockIds.isEmpty();
        List<DocumentPatchOperation> operations = new ArrayList<>();
        DocumentPatchOperationType commandType;
        boolean requiresApproval;
        if (exactBlock) {
            commandType = DocumentPatchOperationType.BLOCK_REPLACE;
            requiresApproval = false;
            operations.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.BLOCK_REPLACE)
                    .blockId(blockIds.get(0))
                    .oldText(selector.getMatchedExcerpt())
                    .newContent(generated)
                    .docFormat("markdown")
                    .justification("按唯一命中 block 执行替换")
                    .build());
        } else if (sectionBlocks) {
            commandType = DocumentPatchOperationType.BLOCK_INSERT_AFTER;
            requiresApproval = blockIds.size() > 6;
            operations.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                    .blockId(blockIds.get(0))
                    .newContent(generated)
                    .docFormat("markdown")
                    .justification("在章节标题后插入新的章节正文")
                    .build());
            if (blockIds.size() > 1) {
                operations.add(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                        .blockId(String.join(",", blockIds.subList(1, blockIds.size())))
                        .oldText(selector.getMatchedExcerpt())
                        .justification("删除旧的章节正文 block，保留章节标题")
                        .build());
            }
        } else {
            commandType = DocumentPatchOperationType.STR_REPLACE;
            requiresApproval = true;
            operations.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.STR_REPLACE)
                    .oldText(selector.getMatchedExcerpt())
                    .newContent(generated)
                    .docFormat("markdown")
                    .justification("无法稳定收敛到 block，退回文本替换兜底")
                    .build());
        }
        return DocumentEditPlan.builder()
                .taskId(taskId)
                .intentType(intentType)
                .selector(selector)
                .reasoningSummary(styleOnly ? "保持事实不变，按要求调整表达风格" : "基于目标片段执行内容改写")
                .generatedContent(generated)
                .toolCommandType(commandType)
                .requiresApproval(requiresApproval)
                .riskLevel(requiresApproval ? DocumentRiskLevel.HIGH : DocumentRiskLevel.MEDIUM)
                .patchOperations(operations)
                .build();
    }

    private DocumentEditPlan buildDeletePlan(String taskId, DocumentTargetSelector selector, String instruction) {
        List<String> blockIds = selector.getMatchedBlockIds() == null ? List.of() : selector.getMatchedBlockIds();
        boolean exactBlock = selector.getLocatorStrategy() == DocumentLocatorStrategy.BY_EXACT_TEXT && blockIds.size() == 1;
        boolean sectionDelete = selector.getLocatorStrategy() == DocumentLocatorStrategy.BY_HEADING && !blockIds.isEmpty();
        List<DocumentPatchOperation> operations = new ArrayList<>();
        DocumentPatchOperationType commandType;
        boolean requiresApproval;
        if (exactBlock) {
            commandType = DocumentPatchOperationType.BLOCK_DELETE;
            requiresApproval = true;
            operations.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                    .blockId(blockIds.get(0))
                    .oldText(selector.getMatchedExcerpt())
                    .justification("删除唯一命中的目标 block，需人工确认")
                    .build());
        } else if (sectionDelete) {
            DeleteScope deleteScope = classifyDeleteScope(selector, instruction);
            List<String> targetBlockIds = deleteScope == DeleteScope.BODY_ONLY && blockIds.size() > 1
                    ? blockIds.subList(1, blockIds.size())
                    : blockIds;
            if (targetBlockIds.isEmpty()) {
                throw new IllegalStateException("目标章节没有可删除的正文内容");
            }
            commandType = DocumentPatchOperationType.BLOCK_DELETE;
            requiresApproval = true;
            operations.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                    .blockId(String.join(",", targetBlockIds))
                    .oldText(selector.getMatchedExcerpt())
                    .justification(deleteScope == DeleteScope.BODY_ONLY ? "删除章节正文，保留章节标题，需人工确认" : "删除整节内容，需人工确认")
                    .build());
        } else {
            commandType = DocumentPatchOperationType.STR_REPLACE;
            requiresApproval = true;
            operations.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.STR_REPLACE)
                    .oldText(selector.getMatchedExcerpt())
                    .newContent("")
                    .docFormat("markdown")
                    .justification("无法唯一定位 block，退回文本删除兜底")
                    .build());
        }
        return DocumentEditPlan.builder()
                .taskId(taskId)
                .intentType(DocumentIterationIntentType.DELETE)
                .selector(selector)
                .reasoningSummary("删除已定位到的目标片段")
                .generatedContent("")
                .toolCommandType(commandType)
                .requiresApproval(requiresApproval)
                .riskLevel(requiresApproval ? DocumentRiskLevel.HIGH : DocumentRiskLevel.MEDIUM)
                .patchOperations(operations)
                .build();
    }

    private DeleteScope classifyDeleteScope(DocumentTargetSelector selector, String instruction) {
        String prompt = """
                你是文档删除范围判定器。
                请根据用户指令和当前命中的文档片段，判断删除范围。
                只能输出一个枚举值，不要解释：
                BODY_ONLY
                WHOLE_SECTION

                规则：
                1. 如果用户表达的是删除某节的“内容/正文/下面内容/这一段说明”，输出 BODY_ONLY。
                2. 如果用户表达的是删除整个小节/整节/整个章节/标题连同内容一起删除，输出 WHOLE_SECTION。
                3. 无法确定时，默认输出 WHOLE_SECTION。

                用户指令：
                %s

                当前命中片段：
                %s
                """.formatted(instruction == null ? "" : instruction, selector.getMatchedExcerpt());
        String response = trim(chatModel.call(prompt)).toUpperCase(Locale.ROOT);
        return "BODY_ONLY".equals(response) ? DeleteScope.BODY_ONLY : DeleteScope.WHOLE_SECTION;
    }

    private String explain(String excerpt, String instruction) {
        String prompt = """
                你是企业文档解释助手。
                基于下面的文档片段回答用户问题，要求：
                1. 只解释片段本身表达的意思，不编造文档外事实。
                2. 用简洁中文输出 3-6 句。
                3. 如果用户问题聚焦某个点，优先解释该点。

                用户指令：
                %s

                文档片段：
                %s
                """.formatted(instruction, excerpt);
        return trim(chatModel.call(prompt));
    }

    private String rewrite(DocumentTargetSelector selector, String instruction, boolean styleOnly) {
        boolean sectionRewrite = selector.getLocatorStrategy() == DocumentLocatorStrategy.BY_HEADING;
        String prompt = """
                你是企业文档精修助手。
                请严格基于原文片段和用户指令，输出可直接替换回文档的 Markdown 片段。
                规则：
                1. 只返回改写后的 Markdown，不要解释，不要代码块。
                2. %s
                3. 保持结构清晰，必要时可使用小标题或列表。
                4. %s

                用户指令：
                %s

                原文片段：
                %s
                """.formatted(
                styleOnly ? "保持主事实和结论边界，不新增未经原文支持的新事实" : "允许在原文范围内改写、补强和重组，但不要偏离主题",
                sectionRewrite ? "当前定位是整节正文，不要重复当前 H2 标题本身，只输出该节标题下的正文" : "当前定位是单个 block，可直接输出替换该 block 的完整内容",
                instruction,
                selector.getMatchedExcerpt()
        );
        String generated = trim(chatModel.call(prompt));
        return sectionRewrite ? stripLeadingHeading(generated, selector.getLocatorValue()) : generated;
    }

    private String insertAfter(String excerpt, String instruction) {
        String prompt = """
                你是企业文档补写助手。
                请基于当前章节上下文和用户指令，输出一段可直接插入文档的 Markdown 内容。
                规则：
                1. 只返回新增内容本身，不要解释，不要代码块。
                2. 新内容必须与上下文衔接自然，不要重复原文。
                3. 如果是在现有章节前后新增同级章节，新标题要与锚点标题保持同级。
                4. 如果用户要求新增一个小节，再使用更低一级的小标题。
                5. 如果用户要求新增“章节/一节/一个部分”，输出必须包含标题和正文，不能只给空标题。
                6. 如果新增的是完整章节，标题后至少补一段正文，必要时可继续补 H3 小节。

                用户指令：
                %s

                当前章节内容：
                %s
                """.formatted(instruction, excerpt);
        return trim(chatModel.call(prompt));
    }

    private String normalizeInsertedHeadingLevel(String generated, String anchorExcerpt) {
        String trimmed = trim(generated);
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        int anchorLevel = extractHeadingLevel(anchorExcerpt);
        if (anchorLevel <= 0) {
            return trimmed;
        }
        String[] lines = trimmed.split("\\R", -1);
        if (lines.length == 0) {
            return trimmed;
        }
        String firstLine = lines[0].trim();
        if (!firstLine.startsWith("#")) {
            if (looksLikeStructuredHeading(firstLine) && hasNonBlankBody(lines)) {
                lines[0] = "#".repeat(anchorLevel) + " " + firstLine;
                return trim(String.join("\n", lines));
            }
            return trimmed;
        }
        String headingText = firstLine.replaceFirst("^#+\\s*", "");
        lines[0] = "#".repeat(anchorLevel) + " " + headingText;
        return trim(String.join("\n", lines));
    }

    private int extractHeadingLevel(String markdown) {
        String trimmed = trim(markdown);
        if (trimmed.isEmpty()) {
            return -1;
        }
        String firstLine = trimmed.split("\\R", 2)[0].trim();
        if (!firstLine.startsWith("#")) {
            return -1;
        }
        int level = 0;
        while (level < firstLine.length() && firstLine.charAt(level) == '#') {
            level++;
        }
        return level;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String stripLeadingHeading(String markdown, String headingText) {
        String trimmed = trim(markdown);
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        String[] lines = trimmed.split("\\R", -1);
        if (lines.length == 0) {
            return trimmed;
        }
        String firstLine = lines[0].trim();
        if (!firstLine.startsWith("#")) {
            return trimmed;
        }
        String normalizedHeading = normalizeHeadingText(headingText);
        String normalizedFirstLine = normalizeHeadingText(firstLine.replaceFirst("^#+\\s*", ""));
        if (!normalizedFirstLine.equals(normalizedHeading)) {
            return trimmed;
        }
        int index = 1;
        while (index < lines.length && lines[index].isBlank()) {
            index++;
        }
        return trim(String.join("\n", java.util.Arrays.copyOfRange(lines, index, lines.length)));
    }

    private String normalizeHeadingText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private String firstHeadingText(String markdown, String fallback) {
        String trimmed = trim(markdown);
        if (!trimmed.isEmpty()) {
            String firstLine = trimmed.split("\\R", 2)[0].trim();
            if (firstLine.startsWith("#")) {
                String heading = trim(firstLine.replaceFirst("^#+\\s*", ""));
                if (!heading.isEmpty()) {
                    return heading;
                }
            }
        }
        return trim(fallback);
    }

    private String buildInlineHeadingPrependContent(String generated, String anchorExcerpt, String anchorTitle) {
        String trimmedGenerated = trim(generated);
        if (trimmedGenerated.isEmpty()) {
            return "";
        }
        String[] lines = trimmedGenerated.split("\\R", -1);
        if (lines.length == 0) {
            return trimmedGenerated;
        }
        String firstLine = lines[0].trim();
        if (!firstLine.startsWith("#")) {
            return trimmedGenerated + "\n\n" + anchorExcerpt;
        }
        String generatedHeadingText = trim(firstLine.replaceFirst("^#+\\s*", ""));
        int bodyStart = 1;
        while (bodyStart < lines.length && lines[bodyStart].isBlank()) {
            bodyStart++;
        }
        String body = bodyStart >= lines.length ? "" : trim(String.join("\n", java.util.Arrays.copyOfRange(lines, bodyStart, lines.length)));
        StringBuilder builder = new StringBuilder(generatedHeadingText);
        if (!body.isEmpty()) {
            builder.append("\n\n").append(body);
        }
        if (anchorExcerpt != null && !anchorExcerpt.isBlank()) {
            builder.append("\n\n").append(anchorExcerpt);
        } else if (anchorTitle != null && !anchorTitle.isBlank()) {
            builder.append("\n\n## ").append(anchorTitle);
        }
        return trim(builder.toString());
    }

    private String buildBlockReplacePrependContent(String generated, String anchorExcerpt) {
        String trimmedGenerated = trim(generated);
        String trimmedAnchor = trim(anchorExcerpt);
        if (trimmedGenerated.isEmpty()) {
            return trimmedAnchor;
        }
        if (trimmedAnchor.isEmpty()) {
            return trimmedGenerated;
        }
        return trim(trimmedGenerated + "\n\n" + trimmedAnchor);
    }

    private boolean looksLikeHeadingOnly(String markdown) {
        String trimmed = trim(markdown);
        if (trimmed.isEmpty()) {
            return false;
        }
        String[] lines = trimmed.split("\\R");
        if (lines.length == 0) {
            return false;
        }
        if (!lines[0].trim().startsWith("#")) {
            return false;
        }
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return false;
            }
        }
        return true;
    }

    private boolean looksLikeStructuredHeading(String line) {
        String trimmed = trim(line);
        return !trimmed.isEmpty() && STRUCTURED_HEADING_PATTERN.matcher(trimmed).matches();
    }

    private boolean hasNonBlankBody(String[] lines) {
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return true;
            }
        }
        return false;
    }
}
