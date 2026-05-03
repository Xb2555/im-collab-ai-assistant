package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentPatchOperation;
import com.lark.imcollab.common.model.enums.DocumentPatchOperationType;
import com.lark.imcollab.skills.lark.doc.LarkDocFetchResult;
import com.lark.imcollab.skills.lark.doc.LarkDocBlockRef;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import com.lark.imcollab.skills.lark.doc.LarkDocUpdateResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentPatchExecutor {

    private final LarkDocTool larkDocTool;

    public DocumentPatchExecutor(LarkDocTool larkDocTool) {
        this.larkDocTool = larkDocTool;
    }

    public PatchExecutionResult execute(String docRef, DocumentEditPlan plan) {
        List<String> modifiedBlocks = new ArrayList<>();
        if (plan == null || plan.getPatchOperations() == null || plan.getPatchOperations().isEmpty()) {
            return new PatchExecutionResult(modifiedBlocks, -1L, -1L);
        }
        long beforeRevision = -1L;
        long afterRevision = -1L;
        String lastNewBlockId = null;
        for (DocumentPatchOperation operation : plan.getPatchOperations()) {
            operation = resolveNewBlockPlaceholder(operation, lastNewBlockId);
            LarkDocFetchResult beforeMarkdown = larkDocTool.fetchDocFullMarkdown(docRef);
            LarkDocFetchResult beforeXml = larkDocTool.fetchDocFull(docRef, "with-ids");
            if (beforeRevision < 0) {
                beforeRevision = beforeMarkdown.getRevisionId();
            }
            LarkDocUpdateResult result = switch (operation.getOperationType()) {
                case STR_REPLACE -> larkDocTool.updateByCommand(
                        docRef,
                        "str_replace",
                        operation.getNewContent(),
                        operation.getDocFormat(),
                        null,
                        operation.getOldText(),
                        beforeMarkdown.getRevisionId()
                );
                case BLOCK_INSERT_AFTER -> larkDocTool.updateByCommand(
                        docRef,
                        "block_insert_after",
                        operation.getNewContent(),
                        operation.getDocFormat(),
                        operation.getBlockId(),
                        null,
                        beforeMarkdown.getRevisionId()
                );
                case BLOCK_DELETE -> larkDocTool.updateByCommand(
                        docRef,
                        "block_delete",
                        null,
                        null,
                        operation.getBlockId(),
                        null,
                        beforeMarkdown.getRevisionId()
                );
                case BLOCK_REPLACE -> larkDocTool.updateByCommand(
                        docRef,
                        "block_replace",
                        operation.getNewContent(),
                        operation.getDocFormat(),
                        operation.getBlockId(),
                        null,
                        beforeMarkdown.getRevisionId()
                    );
                case APPEND -> larkDocTool.updateByCommand(
                        docRef,
                        "append",
                        operation.getNewContent(),
                        operation.getDocFormat(),
                        null,
                        null,
                        beforeMarkdown.getRevisionId()
                );
                case BLOCK_MOVE_AFTER -> larkDocTool.updateByCommand(
                        docRef,
                        "block_move_after",
                        null,
                        null,
                        operation.getBlockId(),
                        operation.getTargetBlockId(),
                        beforeMarkdown.getRevisionId()
                );
                default -> throw new IllegalStateException("Unsupported patch operation: " + operation.getOperationType());
            };
            LarkDocFetchResult afterMarkdownFetch = larkDocTool.fetchDocFullMarkdown(docRef);
            LarkDocFetchResult afterXmlFetch = larkDocTool.fetchDocFull(docRef, "with-ids");
            verifyOperation(operation, result, beforeMarkdown, beforeXml, afterMarkdownFetch, afterXmlFetch);
            afterRevision = afterMarkdownFetch.getRevisionId();
            collectModifiedBlocks(modifiedBlocks, operation, result);
            lastNewBlockId = extractFirstNewBlockId(result);
        }
        return new PatchExecutionResult(modifiedBlocks, beforeRevision, afterRevision);
    }

    private DocumentPatchOperation resolveNewBlockPlaceholder(DocumentPatchOperation operation, String lastNewBlockId) {
        if (lastNewBlockId == null || !"__new__".equals(operation.getBlockId())) {
            return operation;
        }
        return DocumentPatchOperation.builder()
                .operationType(operation.getOperationType())
                .blockId(lastNewBlockId)
                .targetBlockId(operation.getTargetBlockId())
                .startBlockId(operation.getStartBlockId())
                .endBlockId(operation.getEndBlockId())
                .oldText(operation.getOldText())
                .newContent(operation.getNewContent())
                .docFormat(operation.getDocFormat())
                .justification(operation.getJustification())
                .build();
    }

    private String extractFirstNewBlockId(LarkDocUpdateResult result) {
        if (result == null || result.getNewBlocks() == null || result.getNewBlocks().isEmpty()) {
            return null;
        }
        return result.getNewBlocks().stream()
                .map(LarkDocBlockRef::getBlockId)
                .filter(id -> id != null && !id.isBlank())
                .findFirst()
                .orElse(null);
    }

    private void verifyOperation(
            DocumentPatchOperation operation,
            LarkDocUpdateResult result,
            LarkDocFetchResult beforeMarkdown,
            LarkDocFetchResult beforeXml,
            LarkDocFetchResult afterMarkdown,
            LarkDocFetchResult afterXml
    ) {
        if (operation.getOperationType() == DocumentPatchOperationType.STR_REPLACE) {
            verifyStringReplacePreconditions(operation, beforeMarkdown.getContent());
        }
        if (afterMarkdown.getRevisionId() <= beforeMarkdown.getRevisionId()) {
            if (operation.getOperationType() == DocumentPatchOperationType.STR_REPLACE) {
                throw new IllegalStateException("str_replace 未生效：revision 未推进，请检查 pattern 是否能命中原文");
            }
            throw new IllegalStateException("文档 revision 未推进，无法确认 patch 已成功写入");
        }
        switch (operation.getOperationType()) {
            case STR_REPLACE -> verifyStringReplace(operation, beforeMarkdown.getContent(), afterMarkdown.getContent());
            case BLOCK_REPLACE -> verifyBlockReplace(
                    result,
                    operation,
                    beforeMarkdown.getContent(),
                    beforeXml.getContent(),
                    afterMarkdown.getContent(),
                    afterXml.getContent()
            );
            case BLOCK_INSERT_AFTER -> verifyBlockInsert(result, operation, afterMarkdown.getContent(), afterXml.getContent());
            case BLOCK_DELETE -> verifyBlockDelete(operation, beforeXml.getContent(), afterXml.getContent(), afterMarkdown.getContent());
            case APPEND -> verifyAppend(operation, afterMarkdown.getContent());
            default -> {
            }
        }
    }

    private void verifyStringReplace(DocumentPatchOperation operation, String before, String after) {
        verifyStringReplacePreconditions(operation, before);
        String oldText = normalize(operation.getOldText());
        String newText = normalize(operation.getNewContent());
        if (oldText != null && !oldText.isBlank() && after.contains(operation.getOldText())) {
            throw new IllegalStateException("str_replace 后原文仍存在，校验失败");
        }
        if (newText != null && !newText.isBlank() && !normalize(after).contains(newText)) {
            throw new IllegalStateException("str_replace 后未找到新内容，校验失败");
        }
    }

    private void verifyStringReplacePreconditions(DocumentPatchOperation operation, String before) {
        String oldText = normalize(operation.getOldText());
        if (oldText != null && !oldText.isBlank() && countOccurrences(before, oldText) != 1) {
            throw new IllegalStateException("str_replace 执行前命中数不为 1，拒绝继续");
        }
    }

    private void verifyBlockReplace(
            LarkDocUpdateResult result,
            DocumentPatchOperation operation,
            String beforeMarkdown,
            String beforeXml,
            String afterMarkdown,
            String afterXml
    ) {
        boolean originalBlockPresent = operation.getBlockId() != null && afterXml.contains("id=\"" + operation.getBlockId() + "\"");
        boolean replacementBlocksPresent = result.getNewBlocks() != null
                && !result.getNewBlocks().isEmpty()
                && result.getNewBlocks().stream().map(LarkDocBlockRef::getBlockId)
                .allMatch(blockId -> blockId != null && afterXml.contains("id=\"" + blockId + "\""));
        if (operation.getNewContent() == null || operation.getNewContent().isBlank()) {
            return;
        }
        String normalizedAfter = normalize(afterMarkdown);
        String normalizedBefore = normalize(beforeMarkdown);
        String normalizedNew = normalize(operation.getNewContent());
        if (normalizedAfter.contains(normalizedNew)) {
            return;
        }
        String normalizedOld = normalize(operation.getOldText());
        if (normalizedOld != null && !normalizedOld.isBlank() && normalizedNew.contains(normalizedOld)) {
            int splitIndex = normalizedNew.indexOf(normalizedOld);
            String insertedPrefix = normalizedNew.substring(0, splitIndex);
            String insertedSuffix = normalizedNew.substring(splitIndex + normalizedOld.length());
            if (containsMeaningfulFragment(afterMarkdown, insertedPrefix)
                    && normalizedAfter.contains(normalizedOld)) {
                return;
            }
            if (containsMeaningfulFragment(afterMarkdown, insertedSuffix)
                    && normalizedAfter.contains(normalizedOld)) {
                return;
            }
        }
        if (result.getUpdatedBlocksCount() > 0 && (!originalBlockPresent || replacementBlocksPresent || normalizedOld != null)) {
            return;
        }
        if (containsMeaningfulFragment(afterMarkdown, operation.getNewContent())) {
            return;
        }
        boolean markdownChanged = normalizedBefore != null && !normalizedBefore.equals(normalizedAfter);
        boolean xmlChanged = normalize(beforeXml) != null && !normalize(beforeXml).equals(normalize(afterXml));
        if (result.isSuccess() && (markdownChanged || xmlChanged)) {
            return;
        }
        throw new IllegalStateException("block_replace 后未找到新内容，校验失败");
    }

    private void verifyBlockInsert(LarkDocUpdateResult result, DocumentPatchOperation operation, String afterMarkdown, String afterXml) {
        if (operation.getNewContent() != null && !operation.getNewContent().isBlank()
                && !normalize(afterMarkdown).contains(normalize(operation.getNewContent()))) {
            throw new IllegalStateException("block_insert_after 后未找到插入内容，校验失败");
        }
        if (result.getNewBlocks() == null || result.getNewBlocks().isEmpty()) {
            return;
        }
        boolean allPresent = result.getNewBlocks().stream().map(LarkDocBlockRef::getBlockId)
                .allMatch(blockId -> blockId != null && afterXml.contains("id=\"" + blockId + "\""));
        if (!allPresent) {
            throw new IllegalStateException("block_insert_after 新增 block 未全部落盘，校验失败");
        }
    }

    private void verifyBlockDelete(DocumentPatchOperation operation, String beforeXml, String afterXml, String afterMarkdown) {
        String[] ids = operation.getBlockId().split(",");
        for (String rawId : ids) {
            String blockId = rawId.trim();
            if (blockId.isBlank()) {
                continue;
            }
            if (!beforeXml.contains("id=\"" + blockId + "\"")) {
                throw new IllegalStateException("block_delete 执行前未找到目标 block=" + blockId);
            }
            if (afterXml.contains("id=\"" + blockId + "\"")) {
                throw new IllegalStateException("block_delete 后目标 block 仍存在，校验失败");
            }
        }
        if (operation.getOldText() != null && !operation.getOldText().isBlank()
                && normalize(afterMarkdown).contains(normalize(operation.getOldText()))) {
            throw new IllegalStateException("block_delete 后旧内容仍存在，校验失败");
        }
    }

    private void verifyAppend(DocumentPatchOperation operation, String afterMarkdown) {
        if (operation.getNewContent() != null && !operation.getNewContent().isBlank()
                && !normalize(afterMarkdown).contains(normalize(operation.getNewContent()))) {
            throw new IllegalStateException("append 后未找到新增内容，校验失败");
        }
    }

    private void collectModifiedBlocks(
            List<String> modifiedBlocks,
            DocumentPatchOperation operation,
            LarkDocUpdateResult result
    ) {
        if ((operation.getOperationType() == DocumentPatchOperationType.BLOCK_INSERT_AFTER
                || operation.getOperationType() == DocumentPatchOperationType.APPEND
                || operation.getOperationType() == DocumentPatchOperationType.BLOCK_REPLACE)
                && result.getNewBlocks() != null
                && !result.getNewBlocks().isEmpty()) {
            for (LarkDocBlockRef block : result.getNewBlocks()) {
                if (block.getBlockId() != null && !block.getBlockId().isBlank()) {
                    modifiedBlocks.add(block.getBlockId());
                }
            }
            return;
        }
        if (operation.getOperationType() == DocumentPatchOperationType.BLOCK_INSERT_AFTER) {
            modifiedBlocks.add("insert-after:" + operation.getBlockId());
        } else if (operation.getOperationType() == DocumentPatchOperationType.APPEND) {
            modifiedBlocks.add("append");
        } else if (operation.getBlockId() != null && !operation.getBlockId().isBlank()) {
            modifiedBlocks.add(operation.getBlockId());
        } else if (operation.getOldText() != null && !operation.getOldText().isBlank()) {
            modifiedBlocks.add("text-match");
        }
    }

    private int countOccurrences(String text, String pattern) {
        if (text == null || pattern == null || pattern.isBlank()) {
            return 0;
        }
        int count = 0;
        int fromIndex = 0;
        while (true) {
            int index = text.indexOf(pattern, fromIndex);
            if (index < 0) {
                return count;
            }
            count++;
            fromIndex = index + pattern.length();
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.replaceAll("\\s+", "").trim();
    }

    private boolean containsMeaningfulFragment(String haystack, String candidate) {
        if (haystack == null || haystack.isBlank() || candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalizedHaystack = normalize(haystack);
        if (normalizedHaystack == null || normalizedHaystack.isBlank()) {
            return false;
        }
        for (String fragment : candidate.split("\\R+")) {
            String normalizedFragment = normalize(fragment.replaceAll("^[#>*\\-\\d.\\s]+", ""));
            if (normalizedFragment == null || normalizedFragment.length() < 4) {
                continue;
            }
            if (normalizedHaystack.contains(normalizedFragment)) {
                return true;
            }
        }
        return false;
    }

    @Getter
    @AllArgsConstructor
    public static class PatchExecutionResult {
        private final List<String> modifiedBlocks;
        private final long beforeRevision;
        private final long afterRevision;
    }
}
