package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.*;
import com.lark.imcollab.common.model.enums.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentPatchCompiler {

    private final ChatModel chatModel;

    public DocumentPatchCompiler(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public DocumentEditPlan compile(
            String taskId,
            DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        if (anchor.getAnchorType() == DocumentAnchorType.UNRESOLVED) {
            return approvalOnlyPlan(taskId, intent, snapshot, anchor, strategy,
                    "锚点未解析：" + anchor.getPreview());
        }
        return switch (intent.getSemanticAction()) {
            case EXPLAIN_ONLY -> buildExplainPlan(taskId, intent, snapshot, anchor, strategy);
            case INSERT_METADATA_AT_DOCUMENT_HEAD -> buildControlledHeadInsertPlan(taskId, intent, snapshot, anchor, strategy);
            case INSERT_SECTION_BEFORE_SECTION -> buildInsertBeforeSectionPlan(taskId, intent, snapshot, anchor, strategy);
            case APPEND_SECTION_TO_DOCUMENT_END -> buildAppendPlan(taskId, intent, snapshot, anchor, strategy);
            case REWRITE_METADATA_AT_DOCUMENT_HEAD -> buildMetadataRewritePlan(taskId, intent, snapshot, anchor, strategy);
            case REWRITE_SECTION_BODY -> buildRewriteSectionPlan(taskId, intent, snapshot, anchor, strategy);
            case REWRITE_INLINE_TEXT -> buildInlineRewritePlan(taskId, intent, snapshot, anchor, strategy);
            case REWRITE_SINGLE_BLOCK -> buildSingleBlockRewritePlan(taskId, intent, snapshot, anchor, strategy);
            case INSERT_BLOCK_AFTER_ANCHOR, INSERT_INLINE_TEXT -> buildInsertAfterAnchorPlan(taskId, intent, snapshot, anchor, strategy);
            case DELETE_INLINE_TEXT -> buildInlineDeletePlan(taskId, intent, snapshot, anchor, strategy);
            case DELETE_METADATA_AT_DOCUMENT_HEAD -> buildMetadataDeletePlan(taskId, intent, snapshot, anchor, strategy);
            case DELETE_BLOCK -> buildBlockDeletePlan(taskId, intent, snapshot, anchor, strategy);
            case DELETE_SECTION_BODY -> buildSectionDeletePlan(taskId, intent, snapshot, anchor, strategy, false);
            case DELETE_WHOLE_SECTION -> buildSectionDeletePlan(taskId, intent, snapshot, anchor, strategy, true);
            case MOVE_SECTION, MOVE_BLOCK -> approvalOnlyPlan(taskId, intent, snapshot, anchor, strategy,
                    "移动类编辑需要显式人工确认");
            default -> approvalOnlyPlan(taskId, intent, snapshot, anchor, strategy,
                    "富媒体/布局类编辑由 RichContentExecutionPlanner 处理");
        };
    }

    private DocumentEditPlan buildExplainPlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy) {
        String excerpt = anchorExcerpt(anchor, snapshot);
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary("只读 explain 链路")
                .generatedContent(explain(excerpt, intent.getUserInstruction()))
                .requiresApproval(false)
                .riskLevel(DocumentRiskLevel.LOW)
                .patchOperations(List.of())
                .build();
    }

    private DocumentEditPlan buildControlledHeadInsertPlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy) {
        String excerpt = anchorExcerpt(anchor, snapshot);
        String generated = normalizeHeadingLevel(insertAfter(excerpt, intent.getUserInstruction()), excerpt);
        if (generated.isBlank()) throw new IllegalStateException("文首新增内容为空");
        DocumentSectionAnchor sectionAnchor = anchor.getSectionAnchor();
        if (sectionAnchor == null || sectionAnchor.getHeadingBlockId() == null) {
            return approvalOnlyPlan(taskId, intent, snapshot, anchor, strategy, "未找到首章节锚点，需人工确认");
        }
        List<DocumentPatchOperation> ops = new ArrayList<>();
        ops.add(appendOp(generated, "先追加到文末，后续 move 到文首"));
        String prevId = sectionAnchor.getPrevTopLevelSectionId();
        if (prevId != null) ops.add(groupMoveAfterOp("__new_group__", prevId, "整组移到首章节之前"));
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary("文首插入：append + block_group_move_after")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.APPEND)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.copyOf(ops))
                .build();
    }

    private DocumentEditPlan buildInsertBeforeSectionPlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy) {
        String excerpt = anchorExcerpt(anchor, snapshot);
        String generated = normalizeHeadingLevel(insertAfter(excerpt, intent.getUserInstruction()), excerpt);
        if (generated.isBlank()) throw new IllegalStateException("新增章节为空");
        if (headingOnly(generated)) throw new IllegalStateException("新增章节只有标题无正文");
        DocumentSectionAnchor sectionAnchor = requireSectionAnchor(anchor);
        String headingBlockId = sectionAnchor.getHeadingBlockId();
        String insertContent = generated;
        if (!insertContent.endsWith("\n")) {
            insertContent = insertContent + "\n";
        }
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary("章节前插入：append + block_move_after")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.APPEND)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of(
                        DocumentPatchOperation.builder()
                                .operationType(DocumentPatchOperationType.APPEND)
                                .newContent(insertContent).docFormat("markdown")
                                .justification("先将新章节追加到文末")
                                .build(),
                        DocumentPatchOperation.builder()
                                .operationType(DocumentPatchOperationType.BLOCK_GROUP_MOVE_AFTER)
                                .blockId("__new_group__")
                                .targetBlockId(sectionAnchor.getPrevTopLevelSectionId())
                                .justification("再移动到目标章节前")
                                .build()
                ))
                .build();
    }

    private DocumentEditPlan buildAppendPlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy) {
        String generated = insertAfter("", intent.getUserInstruction());
        if (generated.isBlank()) throw new IllegalStateException("文末追加内容为空");
        if (headingOnly(generated)) throw new IllegalStateException("追加章节没有正文内容");
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary("文末追加新章节")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.APPEND)
                .requiresApproval(strategy.isRequiresApproval())
                .riskLevel(DocumentRiskLevel.MEDIUM)
                .patchOperations(List.of(appendOp(generated, "文末追加")))
                .build();
    }

    private DocumentEditPlan buildRewriteSectionPlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy) {
        DocumentSectionAnchor sectionAnchor = requireSectionAnchor(anchor);
        String excerpt = anchorExcerpt(anchor, snapshot);
        boolean styleOnly = intent.getIntentType() == DocumentIterationIntentType.UPDATE_STYLE;
        String raw = rewriteSection(excerpt, intent.getUserInstruction(), styleOnly);
        String generated = stripLeadingHeading(raw, sectionAnchor.getHeadingText());
        if (generated.isBlank()) throw new IllegalStateException("章节改写结果为空");
        List<DocumentPatchOperation> ops = new ArrayList<>();
        ops.add(DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                .blockId(sectionAnchor.getHeadingBlockId())
                .newContent(generated).docFormat("markdown")
                .justification("在章节标题后写入新正文")
                .build());
        if (sectionAnchor.getBodyBlockIds() != null && !sectionAnchor.getBodyBlockIds().isEmpty()) {
            ops.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                    .blockId(String.join(",", sectionAnchor.getBodyBlockIds()))
                    .justification("删除旧正文 block")
                    .build());
        }
        boolean highRisk = sectionAnchor.getBodyBlockIds() != null && sectionAnchor.getBodyBlockIds().size() > 6;
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary(styleOnly ? "重写章节风格" : "重写章节正文")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                .requiresApproval(highRisk)
                .riskLevel(highRisk ? DocumentRiskLevel.HIGH : DocumentRiskLevel.MEDIUM)
                .patchOperations(List.copyOf(ops))
                .build();
    }

    private DocumentEditPlan buildMetadataRewritePlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy) {
        DocumentBlockAnchor blockAnchor = requireBlockAnchor(anchor);
        String excerpt = blockAnchor.getPlainText() == null ? "" : blockAnchor.getPlainText();
        boolean styleOnly = intent.getIntentType() == DocumentIterationIntentType.UPDATE_STYLE;
        String generated = rewriteInline(excerpt, intent.getUserInstruction(), styleOnly);
        if (generated.isBlank()) throw new IllegalStateException("metadata 改写结果为空");
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary("文首 metadata block 改写")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.BLOCK_REPLACE)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_REPLACE)
                        .blockId(blockAnchor.getBlockId())
                        .oldText(excerpt)
                        .newContent(generated).docFormat("markdown")
                        .justification("文首 metadata block 改写")
                        .build()))
                .build();
    }

    private DocumentEditPlan buildInlineRewritePlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy) {
        DocumentTextAnchor textAnchor = anchor.getTextAnchor();
        String excerpt = textAnchor == null ? "" : textAnchor.getMatchedText();
        boolean styleOnly = intent.getIntentType() == DocumentIterationIntentType.UPDATE_STYLE;
        String generated = rewriteInline(excerpt, intent.getUserInstruction(), styleOnly);
        if (generated.isBlank()) throw new IllegalStateException("行内改写结果为空");
        boolean ambiguous = textAnchor == null || textAnchor.getMatchCount() != 1;
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary("文本锚点改写")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.STR_REPLACE)
                .requiresApproval(ambiguous)
                .riskLevel(ambiguous ? DocumentRiskLevel.HIGH : DocumentRiskLevel.MEDIUM)
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.STR_REPLACE)
                        .oldText(excerpt).newContent(generated).docFormat("markdown")
                        .justification("文本锚点替换")
                        .build()))
                .build();
    }

    private DocumentEditPlan buildSingleBlockRewritePlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy) {
        DocumentBlockAnchor blockAnchor = requireBlockAnchor(anchor);
        String excerpt = blockAnchor.getPlainText() == null ? "" : blockAnchor.getPlainText();
        boolean styleOnly = intent.getIntentType() == DocumentIterationIntentType.UPDATE_STYLE;
        String generated = rewriteInline(excerpt, intent.getUserInstruction(), styleOnly);
        if (generated.isBlank()) throw new IllegalStateException("block 改写结果为空");
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary("block 锚点改写")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.BLOCK_REPLACE)
                .requiresApproval(false)
                .riskLevel(DocumentRiskLevel.MEDIUM)
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_REPLACE)
                        .blockId(blockAnchor.getBlockId())
                        .oldText(excerpt).newContent(generated).docFormat("markdown")
                        .justification("block 锚点替换")
                        .build()))
                .build();
    }

    private DocumentEditPlan buildInsertAfterAnchorPlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy) {
        String excerpt = anchorExcerpt(anchor, snapshot);
        String generated = insertAfter(excerpt, intent.getUserInstruction());
        if (generated.isBlank()) throw new IllegalStateException("插入内容为空");
        String blockId = resolveInsertionBlockId(anchor);
        boolean noAnchor = blockId == null || blockId.isBlank();
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary("锚点后插入")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                .requiresApproval(noAnchor)
                .riskLevel(noAnchor ? DocumentRiskLevel.HIGH : DocumentRiskLevel.MEDIUM)
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                        .blockId(blockId).newContent(generated).docFormat("markdown")
                        .justification("锚点后插入")
                        .build()))
                .build();
    }

    private DocumentEditPlan buildInlineDeletePlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy) {
        DocumentTextAnchor textAnchor = anchor.getTextAnchor();
        String excerpt = textAnchor == null ? "" : textAnchor.getMatchedText();
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary("文本锚点删除")
                .generatedContent("")
                .toolCommandType(DocumentPatchOperationType.STR_REPLACE)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.STR_REPLACE)
                        .oldText(excerpt).newContent("").docFormat("markdown")
                        .justification("删除行内文本")
                        .build()))
                .build();
    }

    private DocumentEditPlan buildMetadataDeletePlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy) {
        DocumentBlockAnchor blockAnchor = requireBlockAnchor(anchor);
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary("文首 metadata block 删除")
                .generatedContent("")
                .toolCommandType(DocumentPatchOperationType.BLOCK_DELETE)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                        .blockId(blockAnchor.getBlockId())
                        .justification("删除文首 metadata block")
                        .build()))
                .build();
    }

    private DocumentEditPlan buildBlockDeletePlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy) {
        DocumentBlockAnchor blockAnchor = requireBlockAnchor(anchor);
        if ("heading".equals(blockAnchor.getBlockType())) {
            throw new IllegalStateException("受保护结构：不允许直接删除标题 block，请使用 DELETE_WHOLE_SECTION");
        }
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary("删除目标 block")
                .generatedContent("")
                .toolCommandType(DocumentPatchOperationType.BLOCK_DELETE)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                        .blockId(blockAnchor.getBlockId())
                        .justification("删除目标 block，需人工确认")
                        .build()))
                .build();
    }

    private DocumentEditPlan buildSectionDeletePlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy, boolean wholeSection) {
        DocumentSectionAnchor sectionAnchor = requireSectionAnchor(anchor);
        List<String> targetIds = wholeSection ? sectionAnchor.getAllBlockIds() : sectionAnchor.getBodyBlockIds();
        if (targetIds == null || targetIds.isEmpty()) {
            throw new IllegalStateException(wholeSection ? "目标章节无可删除 block" : "目标章节无正文内容");
        }
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary(wholeSection ? "删除整节" : "删除章节正文，保留标题")
                .generatedContent("")
                .toolCommandType(DocumentPatchOperationType.BLOCK_DELETE)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of(DocumentPatchOperation.builder()
                        .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                        .blockId(String.join(",", targetIds))
                        .justification(wholeSection ? "删除整节，需人工确认" : "删除章节正文，需人工确认")
                        .build()))
                .build();
    }

    private DocumentEditPlan approvalOnlyPlan(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy, String reason) {
        return base(taskId, intent, snapshot, anchor, strategy)
                .reasoningSummary(reason)
                .generatedContent("")
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of())
                .build();
    }

    private DocumentEditPlan.DocumentEditPlanBuilder base(String taskId, DocumentEditIntent intent,
            DocumentStructureSnapshot snapshot, ResolvedDocumentAnchor anchor, DocumentEditStrategy strategy) {
        return DocumentEditPlan.builder()
                .taskId(taskId)
                .intentType(intent.getIntentType())
                .semanticAction(intent.getSemanticAction())
                .resolvedAnchor(anchor)
                .structureSnapshot(snapshot)
                .strategy(strategy)
                .expectedState(strategy.getExpectedState())
                .strategyType(strategy.getStrategyType());
    }

    // ---- prompt helpers ----

    private String explain(String excerpt, String instruction) {
        return trim(chatModel.call("""
                你是企业文档解释助手。基于下面的文档片段回答用户问题，只解释片段本身，用简洁中文输出 3-6 句。
                用户指令：%s
                文档片段：%s
                """.formatted(instruction, excerpt)));
    }

    private String rewriteSection(String excerpt, String instruction, boolean styleOnly) {
        String rule = styleOnly ? "保持主事实边界，不新增未经原文支持的事实" : "允许在原文范围内改写、补强和重组";
        return trim(chatModel.call("""
                你是企业文档精修助手。输出可直接替换回文档的 Markdown，不要解释，不要代码块。
                规则：%s。当前定位是整节正文，不要重复章节标题本身。
                用户指令：%s
                原文片段：%s
                """.formatted(rule, instruction, excerpt)));
    }

    private String rewriteInline(String excerpt, String instruction, boolean styleOnly) {
        String rule = styleOnly ? "保持主事实边界，不新增未经原文支持的事实" : "允许在原文范围内改写、补强和重组";
        return trim(chatModel.call("""
                你是企业文档精修助手。输出可直接替换回文档的 Markdown，不要解释，不要代码块。
                规则：%s。
                用户指令：%s
                原文片段：%s
                """.formatted(rule, instruction, excerpt)));
    }

    private String insertAfter(String excerpt, String instruction) {
        return trim(chatModel.call("""
                你是企业文档补写助手。输出可直接插入文档的 Markdown，不要解释，不要代码块。
                规则：新内容与上下文衔接自然，不重复原文。如果是新增章节，必须包含标题和正文。
                用户指令：%s
                当前章节内容：%s
                """.formatted(instruction, excerpt)));
    }

    // ---- anchor helpers ----

    private String anchorExcerpt(ResolvedDocumentAnchor anchor, DocumentStructureSnapshot snapshot) {
        if (anchor.getSectionAnchor() != null) {
            return buildSectionExcerpt(snapshot, anchor.getSectionAnchor());
        }
        if (anchor.getBlockAnchor() != null) {
            return trim(anchor.getBlockAnchor().getPlainText());
        }
        if (anchor.getTextAnchor() != null) {
            return trim(anchor.getTextAnchor().getMatchedText());
        }
        return "";
    }

    private String buildSectionExcerpt(DocumentStructureSnapshot snapshot, DocumentSectionAnchor sectionAnchor) {
        if (sectionAnchor == null) return "";
        if (snapshot == null || snapshot.getBlockIndex() == null) return trim(sectionAnchor.getHeadingText());
        StringBuilder sb = new StringBuilder();
        List<String> allIds = sectionAnchor.getAllBlockIds();
        if (allIds == null) return trim(sectionAnchor.getHeadingText());
        for (String id : allIds) {
            DocumentStructureNode node = snapshot.getBlockIndex().get(id);
            if (node != null && node.getPlainText() != null) sb.append(node.getPlainText()).append("\n");
        }
        return trim(sb.toString());
    }

    private String resolveInsertionBlockId(ResolvedDocumentAnchor anchor) {
        if (anchor.getBlockAnchor() != null) return anchor.getBlockAnchor().getBlockId();
        if (anchor.getSectionAnchor() != null) {
            List<String> all = anchor.getSectionAnchor().getAllBlockIds();
            if (all != null && !all.isEmpty()) return all.get(all.size() - 1);
            return anchor.getSectionAnchor().getHeadingBlockId();
        }
        return null;
    }

    private DocumentSectionAnchor requireSectionAnchor(ResolvedDocumentAnchor anchor) {
        if (anchor == null || anchor.getSectionAnchor() == null || anchor.getSectionAnchor().getHeadingBlockId() == null) {
            throw new IllegalStateException("未解析到稳定的 section 锚点");
        }
        return anchor.getSectionAnchor();
    }

    private DocumentBlockAnchor requireBlockAnchor(ResolvedDocumentAnchor anchor) {
        if (anchor == null || anchor.getBlockAnchor() == null || anchor.getBlockAnchor().getBlockId() == null) {
            throw new IllegalStateException("未解析到稳定的 block 锚点");
        }
        return anchor.getBlockAnchor();
    }

    // ---- patch operation builders ----

    private DocumentPatchOperation appendOp(String content, String justification) {
        return DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.APPEND)
                .newContent(content).docFormat("markdown")
                .justification(justification)
                .build();
    }

    private DocumentPatchOperation moveAfterOp(String blockId, String targetBlockId, String justification) {
        return DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.BLOCK_MOVE_AFTER)
                .blockId(blockId).targetBlockId(targetBlockId)
                .justification(justification)
                .build();
    }

    private DocumentPatchOperation groupMoveAfterOp(String groupPlaceholder, String targetBlockId, String justification) {
        return DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.BLOCK_GROUP_MOVE_AFTER)
                .blockId(groupPlaceholder).targetBlockId(targetBlockId)
                .justification(justification)
                .build();
    }

    // ---- text helpers ----

    private String normalizeHeadingLevel(String generated, String anchorExcerpt) {
        String trimmed = trim(generated);
        if (trimmed.isEmpty()) return trimmed;
        int anchorLevel = extractHeadingLevel(anchorExcerpt);
        if (anchorLevel <= 0) return trimmed;
        String[] lines = trimmed.split("\\R", -1);
        if (lines.length == 0) return trimmed;
        String first = lines[0].trim();
        if (first.startsWith("#")) {
            String text = first.replaceFirst("^#+\\s*", "");
            lines[0] = "#".repeat(anchorLevel) + " " + text;
        } else {
            lines[0] = "#".repeat(anchorLevel) + " " + first;
        }
        return trim(String.join("\n", lines));
    }

    private int extractHeadingLevel(String markdown) {
        if (markdown == null || markdown.isBlank()) return -1;
        String first = markdown.trim().split("\\R", 2)[0].trim();
        if (!first.startsWith("#")) return -1;
        int level = 0;
        while (level < first.length() && first.charAt(level) == '#') level++;
        return level;
    }

    private String headingTextOf(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        String first = markdown.trim().split("\\R", 2)[0].trim();
        return first.startsWith("#") ? first.replaceFirst("^#+\\s*", "") : "";
    }

    private String stripLeadingHeading(String markdown, String headingText) {
        String trimmed = trim(markdown);
        if (trimmed.isEmpty() || headingText == null || headingText.isBlank()) return trimmed;
        String[] lines = trimmed.split("\\R", -1);
        if (lines.length == 0 || !lines[0].trim().startsWith("#")) return trimmed;
        String firstText = lines[0].trim().replaceFirst("^#+\\s*", "");
        if (!normalize(firstText).equals(normalize(headingText))) return trimmed;
        int i = 1;
        while (i < lines.length && lines[i].isBlank()) i++;
        return trim(String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length)));
    }

    private boolean headingOnly(String markdown) {
        String trimmed = trim(markdown);
        if (trimmed.isEmpty()) return false;
        String[] lines = trimmed.split("\\R");
        if (!lines[0].trim().startsWith("#")) return false;
        for (int i = 1; i < lines.length; i++) if (!lines[i].isBlank()) return false;
        return true;
    }

    private String normalize(String v) {
        return v == null ? "" : v.replaceAll("\\s+", "").trim();
    }

    private String trim(String v) {
        return v == null ? "" : v.trim();
    }
}
