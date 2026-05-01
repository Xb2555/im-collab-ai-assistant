package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.common.model.entity.DocumentTargetSelector;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentLocatorStrategy;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentEditPlanBuilder {

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
            case DELETE -> buildDeletePlan(taskId, selector);
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
        String generated = insertAfter(selector.getMatchedExcerpt(), instruction);
        String anchorBlockId = selector.getMatchedBlockIds() == null || selector.getMatchedBlockIds().isEmpty()
                ? null
                : selector.getMatchedBlockIds().get(selector.getMatchedBlockIds().size() - 1);
        return DocumentEditPlan.builder()
                .taskId(taskId)
                .intentType(DocumentIterationIntentType.INSERT)
                .selector(selector)
                .reasoningSummary("基于定位到的章节末尾追加新增内容")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                .requiresApproval(anchorBlockId == null)
                .riskLevel(DocumentRiskLevel.MEDIUM)
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                        .blockId(anchorBlockId)
                        .newContent(generated)
                        .docFormat("markdown")
                        .justification("在目标章节末尾新增内容")
                        .build()))
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

    private DocumentEditPlan buildDeletePlan(String taskId, DocumentTargetSelector selector) {
        List<String> blockIds = selector.getMatchedBlockIds() == null ? List.of() : selector.getMatchedBlockIds();
        boolean exactBlock = selector.getLocatorStrategy() == DocumentLocatorStrategy.BY_EXACT_TEXT && blockIds.size() == 1;
        boolean sectionDelete = selector.getLocatorStrategy() == DocumentLocatorStrategy.BY_HEADING && !blockIds.isEmpty();
        List<DocumentPatchOperation> operations = new ArrayList<>();
        DocumentPatchOperationType commandType;
        boolean requiresApproval;
        if (exactBlock) {
            commandType = DocumentPatchOperationType.BLOCK_DELETE;
            requiresApproval = false;
            operations.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                    .blockId(blockIds.get(0))
                    .oldText(selector.getMatchedExcerpt())
                    .justification("删除唯一命中的目标 block")
                    .build());
        } else if (sectionDelete) {
            commandType = DocumentPatchOperationType.BLOCK_DELETE;
            requiresApproval = true;
            operations.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                    .blockId(String.join(",", blockIds))
                    .oldText(selector.getMatchedExcerpt())
                    .justification("删除整节内容，需人工确认")
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
                3. 如果用户要求新增一个小节，可以输出以 ### 开头的小标题。

                用户指令：
                %s

                当前章节内容：
                %s
                """.formatted(instruction, excerpt);
        return trim(chatModel.call(prompt));
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
}
