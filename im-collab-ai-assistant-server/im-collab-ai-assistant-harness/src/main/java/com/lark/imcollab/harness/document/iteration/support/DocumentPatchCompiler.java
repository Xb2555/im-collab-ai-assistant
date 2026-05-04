package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentBlockAnchor;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentEditStrategy;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.common.model.entity.DocumentSectionAnchor;
import com.lark.imcollab.common.model.entity.DocumentStructureNode;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.common.model.entity.DocumentTargetSelector;
import com.lark.imcollab.common.model.entity.DocumentTextAnchor;
import com.lark.imcollab.common.model.entity.ResolvedDocumentAnchor;
import com.lark.imcollab.common.model.enums.DocumentAnchorType;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentLocatorStrategy;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.common.model.enums.DocumentRelativePosition;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import com.lark.imcollab.common.model.enums.DocumentTargetType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class DocumentPatchCompiler {

    private static final Pattern STRUCTURED_HEADING_PATTERN = Pattern.compile(
            "^(?:[一二三四五六七八九十百千万0-9]+[、.．]|[0-9]+(?:\\.[0-9]+)+)\\s*\\S+.*$"
    );

    private enum DeleteScope {
        BODY_ONLY,
        WHOLE_SECTION
    }

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
        DocumentTargetSelector selector = toSelector(snapshot, anchor, intent);
        return switch (intent.getSemanticAction()) {
            case EXPLAIN_ONLY -> buildExplainPlan(taskId, intent, selector, snapshot, anchor, strategy);
            case INSERT_METADATA_AT_DOCUMENT_HEAD -> buildControlledHeadInsertPlan(taskId, intent, selector, snapshot, anchor, strategy);
            case INSERT_SECTION_BEFORE_SECTION -> buildInsertBeforeSectionPlan(taskId, intent, selector, snapshot, anchor, strategy);
            case APPEND_SECTION_TO_DOCUMENT_END -> buildAppendPlan(taskId, intent, selector, snapshot, anchor, strategy);
            case REWRITE_METADATA_AT_DOCUMENT_HEAD -> buildMetadataRewritePlan(taskId, intent, selector, snapshot, anchor, strategy);
            case REWRITE_SECTION_BODY -> buildRewriteSectionPlan(taskId, intent, selector, snapshot, anchor, strategy);
            case REWRITE_INLINE_TEXT -> buildInlineRewritePlan(taskId, intent, selector, snapshot, anchor, strategy);
            case REWRITE_SINGLE_BLOCK -> buildSingleBlockRewritePlan(taskId, intent, selector, snapshot, anchor, strategy);
            case INSERT_BLOCK_AFTER_ANCHOR, INSERT_INLINE_TEXT -> buildInsertAfterAnchorPlan(taskId, intent, selector, snapshot, anchor, strategy);
            case DELETE_INLINE_TEXT -> buildInlineDeletePlan(taskId, intent, selector, snapshot, anchor, strategy);
            case DELETE_METADATA_AT_DOCUMENT_HEAD -> buildMetadataDeletePlan(taskId, intent, selector, snapshot, anchor, strategy);
            case DELETE_BLOCK -> buildBlockDeletePlan(taskId, intent, selector, snapshot, anchor, strategy);
            case DELETE_SECTION_BODY -> buildSectionDeletePlan(taskId, intent, selector, snapshot, anchor, strategy, DeleteScope.BODY_ONLY);
            case DELETE_WHOLE_SECTION -> buildSectionDeletePlan(taskId, intent, selector, snapshot, anchor, strategy, DeleteScope.WHOLE_SECTION);
            case MOVE_SECTION, MOVE_BLOCK -> buildApprovalOnlyPlan(taskId, intent, selector, snapshot, anchor, strategy,
                    "已识别为移动类编辑，当前链路需要显式人工确认，不自动执行");
            default -> buildApprovalOnlyPlan(taskId, intent, selector, snapshot, anchor, strategy,
                    "富媒体/布局类编辑，由 RichContentExecutionPlanner 处理");
        };
    }

    private DocumentEditPlan buildExplainPlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary("已定位目标片段，走只读 explain 链路")
                .generatedContent(explain(selector.getMatchedExcerpt(), intent.getUserInstruction()))
                .toolCommandType(null)
                .requiresApproval(false)
                .riskLevel(DocumentRiskLevel.LOW)
                .patchOperations(List.of())
                .build();
    }

    private DocumentEditPlan buildControlledHeadInsertPlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        String generated = normalizeInsertedHeadingLevel(
                insertAfter(selector.getMatchedExcerpt(), intent.getUserInstruction()),
                selector.getMatchedExcerpt()
        );
        if (generated.isBlank()) {
            throw new IllegalStateException("文首新增内容为空，拒绝生成修改计划");
        }
        DocumentSectionAnchor sectionAnchor = anchor.getSectionAnchor();
        if (sectionAnchor == null || sectionAnchor.getHeadingBlockId() == null) {
            return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                    .reasoningSummary("未找到首个章节锚点，无法执行文首插入，需人工确认")
                    .generatedContent(generated)
                    .toolCommandType(null)
                    .requiresApproval(true)
                    .riskLevel(DocumentRiskLevel.HIGH)
                    .patchOperations(List.of())
                    .build();
        }
        // 两步法：先 append 新内容，再 block_move_after 到首章节之前的前序位置
        // 飞书不支持 insert_before，用 append + move_after(prevSiblingId) 实现
        String prevSiblingId = sectionAnchor.getPrevTopLevelSectionId();
        List<DocumentPatchOperation> operations = new ArrayList<>();
        operations.add(DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.APPEND)
                .newContent(generated)
                .docFormat("markdown")
                .justification("先追加新内容到文档末尾，后续通过 move 移到文首")
                .build());
        if (prevSiblingId != null) {
            operations.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.BLOCK_MOVE_AFTER)
                    .blockId("__new__")
                    .targetBlockId(prevSiblingId)
                    .justification("将新增内容移动到首章节之前")
                    .build());
        }
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary("文首插入走 append + block_move_after 两步法，不拼接原标题")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.APPEND)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.copyOf(operations))
                .build();
    }

    private DocumentEditPlan buildInsertBeforeSectionPlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        String generated = normalizeInsertedHeadingLevel(
                insertAfter(selector.getMatchedExcerpt(), intent.getUserInstruction()),
                selector.getMatchedExcerpt()
        );
        if (generated.isBlank()) {
            throw new IllegalStateException("新增章节为空，拒绝生成插入计划");
        }
        if (looksLikeHeadingOnly(generated)) {
            throw new IllegalStateException("新增章节只生成了标题、没有正文，拒绝直接写入文档");
        }
        DocumentSectionAnchor sectionAnchor = requireSectionAnchor(anchor);
        // 两步法：先 append，再 block_move_after 到目标章节的前序锚点之后
        String prevSiblingId = sectionAnchor.getPrevTopLevelSectionId();
        List<DocumentPatchOperation> operations = new ArrayList<>();
        operations.add(DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.APPEND)
                .newContent(generated)
                .docFormat("markdown")
                .justification("先追加新章节到文档末尾，后续通过 move 移到目标章节之前")
                .build());
        if (prevSiblingId != null) {
            operations.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.BLOCK_MOVE_AFTER)
                    .blockId("__new__")
                    .targetBlockId(prevSiblingId)
                    .justification("将新章节移动到目标章节之前")
                    .build());
        }
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary("章节前插入走 append + block_move_after 两步法，不拼接原标题")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.APPEND)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.copyOf(operations))
                .build();
    }

    private DocumentEditPlan buildAppendPlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        String generated = insertAfter(selector.getMatchedExcerpt(), intent.getUserInstruction());
        if (generated.isBlank()) {
            throw new IllegalStateException("文末追加内容为空，拒绝生成修改计划");
        }
        if (looksLikeHeadingOnly(generated)) {
            throw new IllegalStateException("追加章节只生成了标题、没有正文，拒绝直接写入文档");
        }
        DocumentPatchOperation operation = DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.APPEND)
                .newContent(generated)
                .docFormat("markdown")
                .justification("在文档末尾追加新内容")
                .build();
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary("已定位到文档尾部，直接追加新章节")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.APPEND)
                .requiresApproval(strategy.isRequiresApproval())
                .riskLevel(DocumentRiskLevel.MEDIUM)
                .patchOperations(List.of(operation))
                .build();
    }

    private DocumentEditPlan buildRewriteSectionPlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        DocumentSectionAnchor sectionAnchor = requireSectionAnchor(anchor);
        String generated = rewriteSectionBody(selector, intent.getUserInstruction(), intent.getIntentType() == DocumentIterationIntentType.UPDATE_STYLE);
        if (generated.isBlank()) {
            throw new IllegalStateException("章节改写结果为空，拒绝生成文档修改计划");
        }
        List<DocumentPatchOperation> operations = new ArrayList<>();
        operations.add(DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                .blockId(sectionAnchor.getHeadingBlockId())
                .newContent(generated)
                .docFormat("markdown")
                .justification("在章节标题后写入新的正文内容")
                .build());
        if (sectionAnchor.getBodyBlockIds() != null && !sectionAnchor.getBodyBlockIds().isEmpty()) {
            operations.add(DocumentPatchOperation.builder()
                    .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                    .blockId(String.join(",", sectionAnchor.getBodyBlockIds()))
                    .oldText(selector.getMatchedExcerpt())
                    .justification("删除旧的章节正文 block，保留章节标题")
                    .build());
        }
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary(intent.getIntentType() == DocumentIterationIntentType.UPDATE_STYLE
                        ? "保持事实边界，重写章节正文风格"
                        : "基于目标章节重写正文")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                .requiresApproval(sectionAnchor.getBodyBlockIds() != null && sectionAnchor.getBodyBlockIds().size() > 6)
                .riskLevel((sectionAnchor.getBodyBlockIds() != null && sectionAnchor.getBodyBlockIds().size() > 6)
                        ? DocumentRiskLevel.HIGH : DocumentRiskLevel.MEDIUM)
                .patchOperations(List.copyOf(operations))
                .build();
    }

    private DocumentEditPlan buildMetadataRewritePlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        DocumentBlockAnchor blockAnchor = requireBlockAnchor(anchor, snapshot);
        if (!isHeadMetadataBlock(blockAnchor, snapshot)) {
            throw new IllegalStateException("未定位到稳定的文首 metadata block，拒绝改写");
        }
        ensureDeleteInstructionMatchesBlock(intent, blockAnchor);
        String generated = rewriteInlineText(selector, intent.getUserInstruction(), intent.getIntentType() == DocumentIterationIntentType.UPDATE_STYLE);
        if (generated.isBlank()) {
            throw new IllegalStateException("metadata 改写结果为空，拒绝生成文档修改计划");
        }
        DocumentPatchOperation operation = DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.BLOCK_REPLACE)
                .blockId(blockAnchor.getBlockId())
                .oldText(selector.getMatchedExcerpt())
                .newContent(generated)
                .docFormat("markdown")
                .justification("按文首 metadata block 执行受控改写")
                .build();
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary("已定位到文首 metadata 文本块，按 block 锚点执行改写")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.BLOCK_REPLACE)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of(operation))
                .build();
    }

    private DocumentEditPlan buildInlineRewritePlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        String generated = rewriteInlineText(selector, intent.getUserInstruction(), intent.getIntentType() == DocumentIterationIntentType.UPDATE_STYLE);
        if (generated.isBlank()) {
            throw new IllegalStateException("行内改写结果为空，拒绝生成文档修改计划");
        }
        DocumentPatchOperation operation = DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.STR_REPLACE)
                .oldText(selector.getMatchedExcerpt())
                .newContent(generated)
                .docFormat("markdown")
                .justification("按唯一命中的文本锚点执行替换")
                .build();
        boolean requiresApproval = anchor.getTextAnchor() == null || anchor.getTextAnchor().getMatchCount() != 1;
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary("按文本锚点执行改写")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.STR_REPLACE)
                .requiresApproval(requiresApproval)
                .riskLevel(requiresApproval ? DocumentRiskLevel.HIGH : DocumentRiskLevel.MEDIUM)
                .patchOperations(List.of(operation))
                .build();
    }

    private DocumentEditPlan buildSingleBlockRewritePlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        DocumentBlockAnchor blockAnchor = requireBlockAnchor(anchor, snapshot);
        String generated = rewriteInlineText(selector, intent.getUserInstruction(), intent.getIntentType() == DocumentIterationIntentType.UPDATE_STYLE);
        if (generated.isBlank()) {
            throw new IllegalStateException("block 改写结果为空，拒绝生成文档修改计划");
        }
        DocumentPatchOperation operation = DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.BLOCK_REPLACE)
                .blockId(blockAnchor.getBlockId())
                .oldText(selector.getMatchedExcerpt())
                .newContent(generated)
                .docFormat("markdown")
                .justification("按 block 锚点替换单个目标 block")
                .build();
        boolean requiresApproval = blockAnchor.getBlockId() == null || blockAnchor.getBlockId().isBlank();
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary("按 block 锚点执行单块改写")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.BLOCK_REPLACE)
                .requiresApproval(requiresApproval)
                .riskLevel(requiresApproval ? DocumentRiskLevel.HIGH : DocumentRiskLevel.MEDIUM)
                .patchOperations(List.of(operation))
                .build();
    }

    private DocumentEditPlan buildInsertAfterAnchorPlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        String generated = insertAfter(selector.getMatchedExcerpt(), intent.getUserInstruction());
        if (generated.isBlank()) {
            throw new IllegalStateException("插入内容为空，拒绝生成修改计划");
        }
        String blockId = resolveInsertionBlockId(anchor);
        DocumentPatchOperation operation = DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                .blockId(blockId)
                .newContent(generated)
                .docFormat("markdown")
                .justification("在已解析锚点后插入新增内容")
                .build();
        boolean requiresApproval = blockId == null || blockId.isBlank();
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary("基于锚点在后方插入新增内容")
                .generatedContent(generated)
                .toolCommandType(DocumentPatchOperationType.BLOCK_INSERT_AFTER)
                .requiresApproval(requiresApproval)
                .riskLevel(requiresApproval ? DocumentRiskLevel.HIGH : DocumentRiskLevel.MEDIUM)
                .patchOperations(List.of(operation))
                .build();
    }

    private DocumentEditPlan buildInlineDeletePlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        DocumentPatchOperation operation = DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.STR_REPLACE)
                .oldText(selector.getMatchedExcerpt())
                .newContent("")
                .docFormat("markdown")
                .justification("删除唯一命中的行内文本")
                .build();
        boolean requiresApproval = anchor.getTextAnchor() == null || anchor.getTextAnchor().getMatchCount() != 1;
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary("按文本锚点删除行内内容")
                .generatedContent("")
                .toolCommandType(DocumentPatchOperationType.STR_REPLACE)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of(operation))
                .build();
    }

    private DocumentEditPlan buildBlockDeletePlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        DocumentBlockAnchor blockAnchor = requireBlockAnchor(anchor, snapshot);
        ensureDeleteInstructionMatchesBlock(intent, blockAnchor);
        DocumentPatchOperation operation = DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                .blockId(blockAnchor.getBlockId())
                .oldText(selector.getMatchedExcerpt())
                .justification("删除唯一命中的目标 block，需人工确认")
                .build();
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary("删除已定位到的单个 block")
                .generatedContent("")
                .toolCommandType(DocumentPatchOperationType.BLOCK_DELETE)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of(operation))
                .build();
    }

    private DocumentEditPlan buildMetadataDeletePlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        DocumentBlockAnchor blockAnchor = requireBlockAnchor(anchor, snapshot);
        if (!isHeadMetadataBlock(blockAnchor, snapshot)) {
            throw new IllegalStateException("未定位到稳定的文首 metadata block，拒绝删除");
        }
        ensureDeleteInstructionMatchesBlock(intent, blockAnchor);
        DocumentPatchOperation operation = DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                .blockId(blockAnchor.getBlockId())
                .oldText(selector.getMatchedExcerpt())
                .justification("删除文首 metadata 文本块，保留文档标题与正文结构")
                .build();
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary("已定位到文首 metadata 文本块，删除时保留标题和首章节结构")
                .generatedContent("")
                .toolCommandType(DocumentPatchOperationType.BLOCK_DELETE)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of(operation))
                .build();
    }

    private DocumentEditPlan buildSectionDeletePlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy,
            DeleteScope deleteScope
    ) {
        DocumentSectionAnchor sectionAnchor = requireSectionAnchor(anchor);
        List<String> targetBlockIds = deleteScope == DeleteScope.BODY_ONLY
                ? sectionAnchor.getBodyBlockIds()
                : sectionAnchor.getAllBlockIds();
        if (targetBlockIds == null || targetBlockIds.isEmpty()) {
            throw new IllegalStateException(deleteScope == DeleteScope.BODY_ONLY ? "目标章节没有可删除的正文内容" : "目标章节没有可删除的 block");
        }
        DocumentPatchOperation operation = DocumentPatchOperation.builder()
                .operationType(DocumentPatchOperationType.BLOCK_DELETE)
                .blockId(String.join(",", targetBlockIds))
                .oldText(selector.getMatchedExcerpt())
                .justification(deleteScope == DeleteScope.BODY_ONLY
                        ? "删除章节正文，保留章节标题，需人工确认"
                        : "删除整节内容，需人工确认")
                .build();
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary(deleteScope == DeleteScope.BODY_ONLY ? "删除章节正文，保留标题" : "删除整节内容")
                .generatedContent("")
                .toolCommandType(DocumentPatchOperationType.BLOCK_DELETE)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of(operation))
                .build();
    }

    private DocumentEditPlan buildApprovalOnlyPlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy,
            String reasoningSummary
    ) {
        return basePlan(taskId, intent, selector, snapshot, anchor, strategy)
                .reasoningSummary(reasoningSummary)
                .generatedContent("")
                .toolCommandType(null)
                .requiresApproval(true)
                .riskLevel(DocumentRiskLevel.HIGH)
                .patchOperations(List.of())
                .build();
    }

    private DocumentEditPlan.DocumentEditPlanBuilder basePlan(
            String taskId,
            DocumentEditIntent intent,
            DocumentTargetSelector selector,
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditStrategy strategy
    ) {
        return DocumentEditPlan.builder()
                .taskId(taskId)
                .intentType(intent.getIntentType())
                .semanticAction(intent.getSemanticAction())
                .selector(selector)
                .resolvedAnchor(anchor)
                .structureSnapshot(snapshot)
                .strategy(strategy)
                .expectedState(strategy.getExpectedState())
                .strategyType(strategy.getStrategyType());
    }

    private DocumentTargetSelector toSelector(
            DocumentStructureSnapshot snapshot,
            ResolvedDocumentAnchor anchor,
            DocumentEditIntent intent
    ) {
        if (anchor.getAnchorType() == DocumentAnchorType.SECTION && anchor.getSectionAnchor() != null) {
            DocumentSectionAnchor sectionAnchor = anchor.getSectionAnchor();
            return DocumentTargetSelector.builder()
                    .docId(snapshot.getDocId())
                    .targetType(DocumentTargetType.SECTION)
                    .locatorStrategy(DocumentLocatorStrategy.BY_HEADING)
                    .relativePosition(intent.getSemanticAction() == DocumentSemanticActionType.INSERT_SECTION_BEFORE_SECTION
                            ? DocumentRelativePosition.BEFORE : DocumentRelativePosition.AFTER)
                    .locatorValue(sectionAnchor.getHeadingText())
                    .matchedBlockIds(sectionAnchor.getAllBlockIds())
                    .matchedExcerpt(buildSectionExcerpt(snapshot, sectionAnchor))
                    .build();
        }
        if (anchor.getAnchorType() == DocumentAnchorType.BLOCK && anchor.getBlockAnchor() != null) {
            DocumentBlockAnchor blockAnchor = anchor.getBlockAnchor();
            return DocumentTargetSelector.builder()
                    .docId(snapshot.getDocId())
                    .targetType(DocumentTargetType.BLOCK)
                    .locatorStrategy(DocumentLocatorStrategy.BY_BLOCK_ID)
                    .relativePosition(DocumentRelativePosition.REPLACE)
                    .locatorValue(trim(blockAnchor.getPlainText()))
                    .matchedBlockIds(blockAnchor.getBlockId() == null ? List.of() : List.of(blockAnchor.getBlockId()))
                    .matchedExcerpt(trim(blockAnchor.getPlainText()))
                    .build();
        }
        if (anchor.getAnchorType() == DocumentAnchorType.DOCUMENT_TAIL) {
            return DocumentTargetSelector.builder()
                    .docId(snapshot.getDocId())
                    .targetType(DocumentTargetType.BLOCK)
                    .locatorStrategy(DocumentLocatorStrategy.DOC_END)
                    .relativePosition(DocumentRelativePosition.AFTER)
                    .locatorValue("DOC_END")
                    .matchedBlockIds(List.of())
                    .matchedExcerpt("")
                    .build();
        }
        if (anchor.getAnchorType() == DocumentAnchorType.DOCUMENT_HEAD) {
            DocumentSectionAnchor sectionAnchor = anchor.getSectionAnchor();
            return DocumentTargetSelector.builder()
                    .docId(snapshot.getDocId())
                    .targetType(DocumentTargetType.TITLE)
                    .locatorStrategy(DocumentLocatorStrategy.DOC_START)
                    .relativePosition(DocumentRelativePosition.BEFORE)
                    .locatorValue(sectionAnchor == null ? "DOC_HEAD" : sectionAnchor.getHeadingText())
                    .matchedBlockIds(sectionAnchor == null || sectionAnchor.getHeadingBlockId() == null
                            ? List.of() : List.of(sectionAnchor.getHeadingBlockId()))
                    .matchedExcerpt(sectionAnchor == null ? "" : buildSectionExcerpt(snapshot, sectionAnchor))
                    .build();
        }
        DocumentTextAnchor textAnchor = anchor.getTextAnchor();
        return DocumentTargetSelector.builder()
                .docId(snapshot.getDocId())
                .targetType(DocumentTargetType.PARAGRAPH)
                .locatorStrategy(DocumentLocatorStrategy.BY_EXACT_TEXT)
                .relativePosition(DocumentRelativePosition.REPLACE)
                .locatorValue(textAnchor == null ? "" : textAnchor.getMatchedText())
                .matchedBlockIds(textAnchor == null || textAnchor.getSourceBlockIds() == null ? List.of() : textAnchor.getSourceBlockIds())
                .matchedExcerpt(textAnchor == null ? "" : trim(textAnchor.getMatchedText()))
                .build();
    }

    private String buildSectionExcerpt(DocumentStructureSnapshot snapshot, DocumentSectionAnchor sectionAnchor) {
        if (snapshot == null || snapshot.getRawFullMarkdown() == null || sectionAnchor == null) {
            return sectionAnchor == null ? "" : trim(sectionAnchor.getHeadingText());
        }
        String heading = trim(sectionAnchor.getHeadingText());
        String markdown = snapshot.getRawFullMarkdown();
        if (heading.isBlank()) {
            return markdown;
        }
        int start = markdown.indexOf(heading);
        if (start < 0) {
            return heading;
        }
        int next = findNextHeading(markdown, start + heading.length());
        if (next < 0) {
            return trim(markdown.substring(start));
        }
        return trim(markdown.substring(start, next));
    }

    private int findNextHeading(String markdown, int fromIndex) {
        String[] lines = markdown.split("\\R", -1);
        int cursor = 0;
        boolean passedCurrent = false;
        for (String line : lines) {
            int lineStart = cursor;
            int lineEnd = cursor + line.length();
            if (!passedCurrent && lineEnd >= fromIndex) {
                passedCurrent = true;
            } else if (passedCurrent && line.trim().startsWith("#")) {
                return lineStart;
            }
            cursor = lineEnd + 1;
        }
        return -1;
    }

    private DocumentSectionAnchor requireSectionAnchor(ResolvedDocumentAnchor anchor) {
        if (anchor == null || anchor.getSectionAnchor() == null || anchor.getSectionAnchor().getHeadingBlockId() == null) {
            throw new IllegalStateException("未解析到稳定的 section 锚点，拒绝继续");
        }
        return anchor.getSectionAnchor();
    }

    private DocumentBlockAnchor requireBlockAnchor(ResolvedDocumentAnchor anchor, DocumentStructureSnapshot snapshot) {
        if (anchor == null || anchor.getBlockAnchor() == null || anchor.getBlockAnchor().getBlockId() == null) {
            throw new IllegalStateException("未解析到稳定的 block 锚点，拒绝继续");
        }
        DocumentBlockAnchor blockAnchor = anchor.getBlockAnchor();
        if (isProtectedBlock(blockAnchor, snapshot)) {
            throw new IllegalStateException("命中的 block 属于受保护结构（标题或章节锚点），拒绝删除");
        }
        return blockAnchor;
    }

    private String resolveInsertionBlockId(ResolvedDocumentAnchor anchor) {
        if (anchor == null) {
            return null;
        }
        if (anchor.getBlockAnchor() != null) {
            return anchor.getBlockAnchor().getBlockId();
        }
        if (anchor.getSectionAnchor() != null) {
            List<String> allBlockIds = anchor.getSectionAnchor().getAllBlockIds();
            if (allBlockIds != null && !allBlockIds.isEmpty()) {
                return allBlockIds.get(allBlockIds.size() - 1);
            }
            return anchor.getSectionAnchor().getHeadingBlockId();
        }
        return null;
    }

    private boolean isHeadMetadataBlock(DocumentBlockAnchor blockAnchor, DocumentStructureSnapshot snapshot) {
        if (blockAnchor == null || snapshot == null || snapshot.getBlockIndex() == null) {
            return false;
        }
        List<String> blockIds = snapshot.getBlockIndex().keySet().stream().toList();
        String firstHeadingId = snapshot.getTopLevelSequence() == null || snapshot.getTopLevelSequence().isEmpty()
                ? null
                : snapshot.getTopLevelSequence().get(0);
        int headingIndex = firstHeadingId == null ? blockIds.size() : blockIds.indexOf(firstHeadingId);
        int blockIndex = blockIds.indexOf(blockAnchor.getBlockId());
        return blockIndex >= 0 && blockIndex < headingIndex && !isProtectedBlock(blockAnchor, snapshot);
    }

    private void ensureDeleteInstructionMatchesBlock(DocumentEditIntent intent, DocumentBlockAnchor blockAnchor) {
        String targetText = trim(blockAnchor.getPlainText());
        if (targetText.isBlank()) {
            throw new IllegalStateException("目标 block 缺少文本内容，拒绝删除");
        }
        String normalizedTarget = normalizeLoose(targetText);
        Set<String> targetKeywords = resolveIntentKeywords(intent);
        if (targetKeywords.isEmpty()) {
            return;
        }
        boolean matched = targetKeywords.stream()
                .map(this::normalizeLoose)
                .filter(keyword -> !keyword.isBlank())
                .anyMatch(normalizedTarget::contains);
        if (matched) {
            return;
        }
        throw new IllegalStateException("删除指令与命中的 block 文本不一致，拒绝继续");
    }

    private boolean isProtectedBlock(DocumentBlockAnchor blockAnchor, DocumentStructureSnapshot snapshot) {
        if (blockAnchor == null || snapshot == null) {
            return false;
        }
        if (snapshot.getHeadingIndex() != null && snapshot.getHeadingIndex().containsKey(blockAnchor.getBlockId())) {
            return true;
        }
        DocumentStructureNode node = snapshot.getBlockIndex() == null ? null : snapshot.getBlockIndex().get(blockAnchor.getBlockId());
        return node != null && ("heading".equalsIgnoreCase(node.getBlockType()) || node.getHeadingLevel() != null);
    }

    private String normalizeLoose(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private Set<String> resolveIntentKeywords(DocumentEditIntent intent) {
        if (intent == null || intent.getParameters() == null) {
            return Set.of();
        }
        String encodedKeywords = intent.getParameters().getOrDefault("targetKeywords", "");
        return Arrays.stream(encodedKeywords.split("\\|"))
                .map(String::trim)
                .filter(keyword -> !keyword.isBlank())
                .collect(java.util.stream.Collectors.toSet());
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

    private String rewriteSectionBody(DocumentTargetSelector selector, String instruction, boolean styleOnly) {
        String prompt = """
                你是企业文档精修助手。
                请严格基于原文片段和用户指令，输出可直接替换回文档的 Markdown 片段。
                规则：
                1. 只返回改写后的 Markdown，不要解释，不要代码块。
                2. %s
                3. 保持结构清晰，必要时可使用小标题或列表。
                4. 当前定位是整节正文，不要重复当前 H2/H3 标题本身，只输出该节标题下的正文。

                用户指令：
                %s

                原文片段：
                %s
                """.formatted(
                styleOnly ? "保持主事实和结论边界，不新增未经原文支持的新事实" : "允许在原文范围内改写、补强和重组，但不要偏离主题",
                instruction,
                selector.getMatchedExcerpt()
        );
        return stripLeadingHeading(trim(chatModel.call(prompt)), selector.getLocatorValue());
    }

    private String rewriteInlineText(DocumentTargetSelector selector, String instruction, boolean styleOnly) {
        String prompt = """
                你是企业文档精修助手。
                请严格基于原文片段和用户指令，输出可直接替换回文档的 Markdown 片段。
                规则：
                1. 只返回改写后的 Markdown，不要解释，不要代码块。
                2. %s
                3. 当前定位是单个文本或 block，可直接输出替换后的完整内容。

                用户指令：
                %s

                原文片段：
                %s
                """.formatted(
                styleOnly ? "保持主事实和结论边界，不新增未经原文支持的新事实" : "允许在原文范围内改写、补强和重组，但不要偏离主题",
                instruction,
                selector.getMatchedExcerpt()
        );
        return trim(chatModel.call(prompt));
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
        return trim(String.join("\n", Arrays.copyOfRange(lines, index, lines.length)));
    }

    private String normalizeHeadingText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
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

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
